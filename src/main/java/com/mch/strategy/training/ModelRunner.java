package com.mch.strategy.training;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mch.strategy.common.AsyncConfigNames;
import com.mch.strategy.common.config.MlPaths;
import com.mch.strategy.job.JobService;
import com.mch.strategy.trainset.TrainingSet;
import com.mch.strategy.trainset.TrainingSetRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 실험 실행 (백그라운드, 한 번에 하나씩).
 * <ul>
 *   <li>학습: 학습 단위마다 표본 고르기 → 결측 채우기 기준 계산 → 모델 학습 → 검증 구간 평가 → 순열 중요도 → 저장</li>
 *   <li>최종 테스트: 저장한 모델을 그대로 불러와 테스트 구간을 평가한다. 실행할 때마다 횟수와 시각을 기록한다.</li>
 * </ul>
 * 실험 폴더(runs/{id}/): model.bin · result_val.json · result_test.json · predictions_val.csv · predictions_test.csv
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRunner {

    static final String MODEL_FILE = "model.bin";
    /** 학습 데이터 파일의 구간 값 */
    static final int TRAIN = 0, VAL = 1, TEST = 2;

    /** 조기 종료 · 학습 재평가 · 순열 중요도에 쓰는 표본 크기 */
    private static final int STOP_ROWS = 20_000;
    private static final int TRAIN_EVAL_ROWS = 100_000;
    private static final int PERMUTATION_ROWS = 20_000;
    private static final int PERMUTATION_REPEATS = 3;
    private static final int BATCH = 5_000;

    private final ModelRunRepository runRepository;
    private final TrainingSetRepository trainingSetRepository;
    private final ModelRunStateService state;
    private final JobService jobService;
    private final MlPaths paths;
    private final ObjectMapper objectMapper;

    public static String resultFile(int split) {
        return split == TEST ? "result_test.json" : "result_val.json";
    }

    // ===================================================== 학습 + 검증

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void train(long runId, long jobId) {
        long started = System.nanoTime();
        try {
            jobService.start(jobId);
            ModelRun run = runRepository.findById(runId).orElseThrow(() -> new IllegalStateException("실험이 지워졌습니다."));
            state.running(runId);
            TrainingSet ts = trainingSetRepository.findById(run.getTrainingSetId())
                    .orElseThrow(() -> new IllegalStateException("학습 데이터가 지워졌습니다."));
            Path dir = paths.runDir(runId);
            MlPaths.deleteTree(dir);
            Files.createDirectories(dir);

            jobService.update(jobId, 2, "학습 데이터 읽기", null);
            try (TrainingData data = new TrainingData(paths.trainingSetDir(ts.getId()), objectMapper)) {
                Trained t = fit(run, ts, data, jobId);
                writeModel(dir.resolve(MODEL_FILE), t.model);

                jobService.update(jobId, 72, "검증 구간 평가", null);
                int[] valIdx = data.indices(VAL, -1);
                Prediction val = predict(t.model, data, valIdx, run.getModelType().isProbability());
                Map<String, Object> result = Evaluator.evaluate(input(t.model, data, valIdx, val), run.getSeed());

                result.put("split", "val");
                result.put("train", t.trainSummary);
                result.put("info", t.info);
                result.put("importance", importance(run, t.model, data, valIdx, jobId));
                writeJson(dir.resolve(resultFile(VAL)), result);
                writePredictions(dir.resolve("predictions_val.csv"), data, valIdx, val);

                ModelRun.Scores trainScores = new ModelRun.Scores((Double) t.trainSummary.get("accuracy"),
                        (Double) t.trainSummary.get("balancedAccuracy"), (Double) t.trainSummary.get("macroF1"),
                        (Double) t.trainSummary.get("logLoss"));
                double seconds = (System.nanoTime() - started) / 1e9;
                state.trained(runId, trainScores, Evaluator.scores(result), Evaluator.baselineMacroF1(result), seconds);
            }
            jobService.succeed(jobId);
            log.info("실험 {} 완료 ({}초)", runId, Math.round((System.nanoTime() - started) / 1e9));
        } catch (Throwable e) {
            log.error("실험 {} 실패", runId, e);
            String message = e instanceof OutOfMemoryError
                    ? "메모리가 부족합니다. 학습 표본 수 상한을 줄이거나 트리 수를 줄여 보세요."
                    : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            state.fail(runId, message);
            jobService.fail(jobId, message);
        }
    }

    private record Trained(RunModel model, Map<String, Object> trainSummary, Map<String, Object> info) {
    }

    private Trained fit(ModelRun run, TrainingSet ts, TrainingData data, long jobId) {
        boolean perAsset = run.getTrainUnit() == RunOptions.TrainUnit.PER_ASSET;
        int units = perAsset ? data.assets.size() : 1;
        Preprocessor[] pres = new Preprocessor[units];
        UnitModel[] models = new UnitModel[units];
        double[][] priors = new double[units][];
        RunModel model = new RunModel(run.getTrainUnit(), data.columns, data.assets, pres, models, priors);
        Random random = new Random(run.getSeed());
        double[] buffer = new double[data.cols];
        List<Map<String, Object>> unitInfo = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        int[] trainLabelAll = new int[0];
        int[] trainPredAll = new int[0];
        double[][] trainProbaAll = run.getModelType().isProbability() ? new double[0][] : null;

        for (int u = 0; u < units; u++) {
            String unitName = perAsset ? data.assets.get(u) : "통합";
            int base = 5 + u * 65 / units;
            int span = 65 / units;
            jobService.update(jobId, base, unitName + " 학습 표본 준비", null);

            int[] trainIdx = sortByTime(data.indices(TRAIN, perAsset ? u : -1), data);
            if (trainIdx.length == 0) {
                throw new IllegalStateException(unitName + " 학습 구간에 행이 없습니다.");
            }
            priors[u] = ModelTrainer.prior(labels(data, trainIdx));

            int[] fitIdx = trainIdx;
            int[] stopIdx = null;
            if (run.getModelType().supportsEarlyStopping()) {
                if (run.getEarlyStopping() == RunOptions.EarlyStopping.TRAIN_TAIL) {
                    int cut = (int) Math.round(trainIdx.length * 0.9);
                    fitIdx = Arrays.copyOfRange(trainIdx, 0, cut);
                    stopIdx = sample(Arrays.copyOfRange(trainIdx, cut, trainIdx.length), STOP_ROWS, random);
                } else if (run.getEarlyStopping() == RunOptions.EarlyStopping.VALIDATION) {
                    stopIdx = sample(data.indices(VAL, perAsset ? u : -1), STOP_ROWS, random);
                }
            }
            int fitRows = fitIdx.length;
            if (run.getBalance() == RunOptions.Balance.UNDERSAMPLE) {
                fitIdx = undersample(fitIdx, data, random);
            }
            int balancedRows = fitIdx.length;
            fitIdx = sample(fitIdx, run.getMaxTrainRows(), random);

            double[][] x = inputs(model, data, fitIdx, buffer);
            int[] y = labels(data, fitIdx);
            Preprocessor pre = Preprocessor.fit(x);
            pre.impute(x);
            pres[u] = pre;
            double[][] stopX = null;
            int[] stopY = null;
            if (stopIdx != null && stopIdx.length > 0) {
                stopX = inputs(model, data, stopIdx, buffer);
                pre.impute(stopX);
                stopY = labels(data, stopIdx);
            }

            final int[] last = {-1};
            BiConsumer<Integer, String> progress = (pct, step) -> {
                int p = base + Math.min(100, pct) * span / 100;
                if (p != last[0]) {
                    last[0] = p;
                    jobService.update(jobId, p, unitName + " 학습 · " + step, null);
                }
            };
            jobService.update(jobId, base + 1, unitName + " 학습 중 (" + String.format("%,d", x.length) + "행 × " + x[0].length + "열)", null);
            StringBuilder unitNotes = new StringBuilder();
            models[u] = ModelTrainer.train(run.getModelType(), run.getParams(), x, y, stopX, stopY, run.getBalance(),
                    run.getSeed(), ts.getThresholdPct().doubleValue(), pre, progress, unitNotes);
            if (!unitNotes.isEmpty()) {
                notes.append(unitName).append(": ").append(unitNotes);
            }

            // 학습 구간 재평가 (표본)
            int[] evalIdx = sample(fitIdx, TRAIN_EVAL_ROWS, random);
            double[][] ex = inputs(model, data, evalIdx, buffer);
            pre.impute(ex);
            int[] ep = new int[ex.length];
            double[][] epr = trainProbaAll == null ? null : new double[ex.length][];
            models[u].predict(ex, previous(data, evalIdx), ep, epr);
            trainLabelAll = concat(trainLabelAll, labels(data, evalIdx));
            trainPredAll = concat(trainPredAll, ep);
            if (trainProbaAll != null) {
                trainProbaAll = concat(trainProbaAll, epr);
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("unit", unitName);
            info.put("trainRows", trainIdx.length);
            info.put("fitPoolRows", fitRows);
            info.put("balancedRows", balancedRows);
            info.put("usedRows", fitIdx.length);
            info.put("stopRows", stopIdx == null ? 0 : stopIdx.length);
            info.put("trainFrom", Instant.ofEpochMilli(data.time[trainIdx[0]]).toString());
            info.put("trainTo", Instant.ofEpochMilli(data.time[trainIdx[trainIdx.length - 1]]).toString());
            info.put("fitTo", Instant.ofEpochMilli(data.time[fitIdx.length == 0 ? trainIdx[0] : maxTime(data, fitIdx)]).toString());
            info.put("prior", priors[u]);
            unitInfo.add(info);
        }

        Map<String, Object> trainSummary = Evaluator.summaryOnly(trainLabelAll, trainPredAll, trainProbaAll);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("units", unitInfo);
        info.put("notes", notes.toString().trim());
        info.put("inputs", model.inputNames());
        info.put("earlyStopping", run.getModelType().supportsEarlyStopping() ? run.getEarlyStopping().getLabel() : "해당 없음");
        info.put("trainedAt", Instant.now().toString());
        info.put("library", "Smile 4.4.2 (순수 Java)");
        return new Trained(model, trainSummary, info);
    }

    // ===================================================== 최종 테스트

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void test(long runId, long jobId) {
        try {
            jobService.start(jobId);
            ModelRun run = runRepository.findById(runId).orElseThrow(() -> new IllegalStateException("실험이 지워졌습니다."));
            Path dir = paths.runDir(runId);
            jobService.update(jobId, 5, "모델 불러오기", null);
            RunModel model = readModel(dir.resolve(MODEL_FILE));
            try (TrainingData data = new TrainingData(paths.trainingSetDir(run.getTrainingSetId()), objectMapper)) {
                int[] testIdx = data.indices(TEST, -1);
                if (testIdx.length == 0) {
                    throw new IllegalStateException("최종 테스트 구간에 행이 없습니다.");
                }
                jobService.update(jobId, 20, "최종 테스트 구간 예측 (" + String.format("%,d", testIdx.length) + "행)", null);
                Prediction p = predict(model, data, testIdx, run.getModelType().isProbability());
                jobService.update(jobId, 80, "평가 지표 계산", null);
                Map<String, Object> result = Evaluator.evaluate(input(model, data, testIdx, p), run.getSeed());
                result.put("split", "test");
                result.put("evaluatedAt", Instant.now().toString());
                result.put("runCount", run.getTestRunCount() + 1);
                writeJson(dir.resolve(resultFile(TEST)), result);
                writePredictions(dir.resolve("predictions_test.csv"), data, testIdx, p);
                state.tested(runId, Evaluator.scores(result));
            }
            jobService.succeed(jobId);
        } catch (Throwable e) {
            log.error("실험 {} 최종 테스트 실패", runId, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            state.testFailed(runId, message);
            jobService.fail(jobId, message);
        }
    }

    // ===================================================== 예측 · 평가 공통

    record Prediction(int[] pred, double[][] proba) {
    }

    /** 행 순서대로 예측. 같은 학습 단위의 연속 구간을 BATCH 행씩 묶어 처리한다. */
    static Prediction predict(RunModel model, TrainingData data, int[] idx, boolean probability) {
        int[] pred = new int[idx.length];
        double[][] proba = probability ? new double[idx.length][] : null;
        double[] buffer = new double[data.cols];
        int k = 0;
        while (k < idx.length) {
            int unit = model.unitOf(data.asset[idx[k]]);
            int end = k;
            while (end < idx.length && end - k < BATCH && model.unitOf(data.asset[idx[end]]) == unit) {
                end++;
            }
            int[] chunk = Arrays.copyOfRange(idx, k, end);
            double[][] x = inputs(model, data, chunk, buffer);
            model.preprocessors()[unit].impute(x);
            int[] p = new int[chunk.length];
            double[][] pr = probability ? new double[chunk.length][] : null;
            model.units()[unit].predict(x, previous(data, chunk), p, pr);
            System.arraycopy(p, 0, pred, k, p.length);
            if (probability) {
                System.arraycopy(pr, 0, proba, k, pr.length);
            }
            k = end;
        }
        return new Prediction(pred, proba);
    }

    private static Evaluator.Input input(RunModel model, TrainingData data, int[] idx, Prediction p) {
        int n = idx.length;
        int[] label = new int[n], asset = new int[n], baseline = new int[n];
        long[] time = new long[n];
        float[] forward = new float[n];
        for (int i = 0; i < n; i++) {
            int r = idx[i];
            label[i] = data.label[r];
            asset[i] = data.asset[r];
            time[i] = data.time[r];
            forward[i] = data.forward[r];
            baseline[i] = model.majority(model.unitOf(asset[i]));
        }
        return new Evaluator.Input(label, p.pred(), p.proba(), time, asset, forward, data.assets, baseline);
    }

    /**
     * 변수 중요도: 모델 자체 중요도 + 검증 표본(최대 2만 행)에서 한 열씩 섞었을 때 Macro F1 이 얼마나 떨어지는지(순열 중요도).
     * 순열 중요도는 학습을 다시 하지 않는다. 상관이 큰 열끼리는 서로 대신해서 낮게 보일 수 있다.
     */
    private Map<String, Object> importance(ModelRun run, RunModel model, TrainingData data, int[] valIdx, long jobId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> names = model.inputNames();
        double[] nativeSum = null;
        boolean nativeOk = true;
        for (UnitModel u : model.units()) {
            double[] imp = u.importance();
            if (imp == null) {
                nativeOk = false;
                break;
            }
            nativeSum = nativeSum == null ? imp.clone() : add(nativeSum, imp);
        }
        out.put("native", nativeOk && nativeSum != null ? ranked(names, nativeSum, null) : null);
        out.put("nativeNote", nativeImportanceNote(run.getModelType()));

        if (!run.isPermutationImportance() || run.getModelType().isBaseline() || valIdx.length == 0) {
            out.put("permutation", null);
            return out;
        }
        Random random = new Random(run.getSeed() + 7);
        int[] sampleIdx = sample(valIdx, PERMUTATION_ROWS, random);
        double[] buffer = new double[data.cols];
        int n = sampleIdx.length;
        double[][] x = new double[n][];
        int[] units = new int[n], label = new int[n];
        double[] prev = new double[n];
        for (int i = 0; i < n; i++) {
            int r = sampleIdx[i];
            units[i] = model.unitOf(data.asset[r]);
            x[i] = model.input(data, r, buffer);
            model.preprocessors()[units[i]].impute(x[i]);
            label[i] = data.label[r];
            prev[i] = data.previous[r];
        }
        double base = macroF1(label, predictPrepared(model, x, units, prev));
        int cols = data.cols; // 자산 구분 열은 섞지 않는다
        double[] mean = new double[cols], std = new double[cols];
        double[] column = new double[n];
        for (int j = 0; j < cols; j++) {
            jobService.update(jobId, 85 + j * 12 / Math.max(1, cols), "순열 중요도 " + (j + 1) + " / " + cols, null);
            for (int i = 0; i < n; i++) {
                column[i] = x[i][j];
            }
            double[] drops = new double[PERMUTATION_REPEATS];
            for (int r = 0; r < PERMUTATION_REPEATS; r++) {
                double[] shuffled = column.clone();
                for (int i = n - 1; i > 0; i--) {
                    int s = random.nextInt(i + 1);
                    double t = shuffled[i];
                    shuffled[i] = shuffled[s];
                    shuffled[s] = t;
                }
                for (int i = 0; i < n; i++) {
                    x[i][j] = shuffled[i];
                }
                drops[r] = base - macroF1(label, predictPrepared(model, x, units, prev));
            }
            for (int i = 0; i < n; i++) {
                x[i][j] = column[i];
            }
            mean[j] = Arrays.stream(drops).average().orElse(0);
            double m = mean[j];
            std[j] = Math.sqrt(Arrays.stream(drops).map(d -> (d - m) * (d - m)).sum() / drops.length);
        }
        out.put("permutation", ranked(names.subList(0, cols), mean, std));
        out.put("permutationRows", n);
        out.put("permutationRepeats", PERMUTATION_REPEATS);
        out.put("permutationBase", base);
        return out;
    }

    private static int[] predictPrepared(RunModel model, double[][] x, int[] units, double[] prev) {
        int[] pred = new int[x.length];
        for (int u = 0; u < model.units().length; u++) {
            List<Integer> rows = new ArrayList<>();
            for (int i = 0; i < x.length; i++) {
                if (units[i] == u) {
                    rows.add(i);
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            double[][] ux = new double[rows.size()][];
            double[] up = new double[rows.size()];
            for (int k = 0; k < ux.length; k++) {
                ux[k] = x[rows.get(k)];
                up[k] = prev[rows.get(k)];
            }
            int[] p = new int[ux.length];
            model.units()[u].predict(ux, up, p, null);
            for (int k = 0; k < p.length; k++) {
                pred[rows.get(k)] = p[k];
            }
        }
        return pred;
    }

    private static double macroF1(int[] label, int[] pred) {
        long[][] cm = new long[3][3];
        for (int i = 0; i < label.length; i++) {
            cm[label[i]][pred[i]]++;
        }
        Double f1 = (Double) Evaluator.summary(cm, null).get("macroF1");
        return f1 == null ? 0 : f1;
    }

    private static String nativeImportanceNote(ModelType type) {
        return switch (type) {
            case LOGISTIC -> "표준화한 입력의 계수 절댓값 평균입니다. 클수록 예측에 크게 반영됩니다 (방향은 클래스마다 다름).";
            case RANDOM_FOREST, DECISION_TREE, GRADIENT_BOOSTING -> "트리가 그 열로 나눌 때 줄인 불순도(또는 손실)의 합입니다. 학습 구간 기준이며 값이 넓은 열이 과대평가될 수 있습니다.";
            default -> "이 모델은 자체 중요도가 없습니다.";
        };
    }

    private static List<Map<String, Object>> ranked(List<String> names, double[] value, double[] std) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int j = 0; j < value.length && j < names.size(); j++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("column", names.get(j));
            m.put("value", value[j]);
            if (std != null) {
                m.put("std", std[j]);
            }
            out.add(m);
        }
        out.sort((a, b) -> Double.compare((Double) b.get("value"), (Double) a.get("value")));
        return out;
    }

    // ===================================================== 표본 · 입력 만들기

    static double[][] inputs(RunModel model, TrainingData data, int[] idx, double[] buffer) {
        double[][] x = new double[idx.length][];
        for (int i = 0; i < idx.length; i++) {
            x[i] = model.input(data, idx[i], buffer);
        }
        return x;
    }

    private static int[] labels(TrainingData data, int[] idx) {
        int[] y = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            y[i] = data.label[idx[i]];
        }
        return y;
    }

    private static double[] previous(TrainingData data, int[] idx) {
        double[] p = new double[idx.length];
        for (int i = 0; i < idx.length; i++) {
            p[i] = data.previous[idx[i]];
        }
        return p;
    }

    /** 시각순(같은 시각이면 자산순) 정렬 — 통합 모델의 「학습 구간 끝 10%」를 시간으로 자르기 위해 */
    private static int[] sortByTime(int[] idx, TrainingData data) {
        return Arrays.stream(idx).boxed()
                .sorted((a, b) -> data.time[a] != data.time[b] ? Long.compare(data.time[a], data.time[b]) : Integer.compare(a, b))
                .mapToInt(Integer::intValue).toArray();
    }

    /** 최대 n 개 무작위 표본 (원래 순서 유지) */
    static int[] sample(int[] idx, int n, Random random) {
        if (idx.length <= n) {
            return idx;
        }
        int[] copy = idx.clone();
        for (int i = 0; i < n; i++) {
            int j = i + random.nextInt(copy.length - i);
            int t = copy[i];
            copy[i] = copy[j];
            copy[j] = t;
        }
        int[] out = Arrays.copyOf(copy, n);
        Arrays.sort(out);
        return out;
    }

    /** 가장 많은 클래스를 두 번째로 많은 클래스 수만큼만 남긴다 */
    static int[] undersample(int[] idx, TrainingData data, Random random) {
        long[] n = new long[3];
        for (int i : idx) {
            n[data.label[i]]++;
        }
        int major = 0;
        for (int c = 1; c < 3; c++) {
            if (n[c] > n[major]) {
                major = c;
            }
        }
        long second = 0;
        for (int c = 0; c < 3; c++) {
            if (c != major) {
                second = Math.max(second, n[c]);
            }
        }
        if (second == 0) {
            return idx;
        }
        final int m = major;
        int[] majorIdx = Arrays.stream(idx).filter(i -> data.label[i] == m).toArray();
        int[] keepMajor = sample(majorIdx, (int) second, random);
        int[] others = Arrays.stream(idx).filter(i -> data.label[i] != m).toArray();
        int[] out = concat(others, keepMajor);
        Arrays.sort(out);
        return out;
    }

    private static int maxTime(TrainingData data, int[] idx) {
        int best = idx[0];
        for (int i : idx) {
            if (data.time[i] > data.time[best]) {
                best = i;
            }
        }
        return best;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static double[][] concat(double[][] a, double[][] b) {
        double[][] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static double[] add(double[] a, double[] b) {
        for (int i = 0; i < a.length && i < b.length; i++) {
            a[i] += b[i];
        }
        return a;
    }

    // ===================================================== 파일

    private void writeJson(Path file, Map<String, Object> result) throws IOException {
        objectMapper.writeValue(file.toFile(), JsonSafe.clean(result));
    }

    private static void writeModel(Path file, RunModel model) throws IOException {
        try (OutputStream os = Files.newOutputStream(file); ObjectOutputStream oos = new ObjectOutputStream(os)) {
            oos.writeObject(model);
        }
    }

    private static RunModel readModel(Path file) throws IOException, ClassNotFoundException {
        if (!Files.exists(file)) {
            throw new IllegalStateException("저장된 모델 파일이 없습니다. 실험을 다시 실행해 주세요.");
        }
        try (InputStream is = Files.newInputStream(file); ObjectInputStream ois = new ObjectInputStream(is)) {
            return (RunModel) ois.readObject();
        }
    }

    /** 예측 기록: 시각(UTC) · 자산 · 실제 정답 · 다음 봉 수익률(%) · 예측 · 클래스 확률 */
    private static void writePredictions(Path file, TrainingData data, int[] idx, Prediction p) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("time_utc,asset,actual,forward_pct,predicted,p_down,p_neutral,p_up\n");
            for (int i = 0; i < idx.length; i++) {
                int r = idx[i];
                w.write(Instant.ofEpochMilli(data.time[r]).toString());
                w.write(',');
                w.write(data.assets.get(data.asset[r]));
                w.write(',');
                w.write(String.valueOf(data.label[r]));
                w.write(',');
                w.write(String.valueOf(data.forward[r]));
                w.write(',');
                w.write(String.valueOf(p.pred()[i]));
                if (p.proba() != null) {
                    double[] pr = p.proba()[i];
                    w.write(String.format(",%.5f,%.5f,%.5f", pr[0], pr[1], pr[2]));
                } else {
                    w.write(",,,");
                }
                w.write('\n');
            }
        }
    }
}
