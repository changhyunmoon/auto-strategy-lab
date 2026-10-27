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
import java.util.function.IntFunction;

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
 *   <li>학습: 학습 단위마다 표본 고르기 → 결측 채우기 기준 계산 → 모델 학습 → 검증(한 번 나누기 또는 워크포워드) → 순열 중요도 → 기본 규칙 거래 성과 → 저장</li>
 *   <li>최종 테스트: 저장한 모델을 그대로 불러와 테스트 구간을 평가한다. 실행할 때마다 횟수와 시각을 기록한다.</li>
 * </ul>
 * 실험 폴더(runs/{id}/): model.bin · result_val.json · result_test.json · predictions_*.csv · pred_*.bin (거래 성과 계산용)
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
                boolean proba = run.getModelType().isProbability();
                Map<String, Object> result;
                Trained finalModel;
                ModelRun.FoldStats foldStats = null;
                int[] evalIdx;
                Prediction evalPred;

                if (!run.getValidation().isWalkForward()) {
                    finalModel = fit(run, data, u -> data.indices(TRAIN, u), u -> data.indices(VAL, u), jobId, 5, 65, "");
                    jobService.update(jobId, 72, "검증 구간 평가", null);
                    evalIdx = data.indices(VAL, -1);
                    evalPred = predict(finalModel.model, data, evalIdx, proba);
                    result = Evaluator.evaluate(input(finalModel.model, data, evalIdx, evalPred), run.getSeed());
                    result.put("importance", importance(run, finalModel.model, data, evalIdx, jobId));
                } else {
                    WalkForwardResult wf = walkForward(run, data, jobId);
                    evalIdx = wf.oofIdx;
                    evalPred = wf.oofPred;
                    result = Evaluator.evaluate(input(wf.lastFoldModel, data, evalIdx, evalPred), run.getSeed());
                    result.put("folds", wf.folds);
                    foldStats = wf.stats;
                    result.put("importance", importance(run, wf.lastFoldModel, data, wf.lastFoldVal, jobId));
                    jobService.update(jobId, 92, "최종 모델: 학습 + 검증 구간 전체로 다시 학습", null);
                    finalModel = fit(run, data, u -> exploration(data, u), u -> new int[0], jobId, 92, 5, "최종 · ");
                }
                writeModel(dir.resolve(MODEL_FILE), finalModel.model);

                Map<String, Object> trade = defaultBacktest(data, evalIdx, evalPred, proba);
                result.put("split", "val");
                result.put("validation", validationInfo(run, data));
                result.put("train", finalModel.trainSummary);
                result.put("info", finalModel.info);
                result.put("backtest", trade);
                writeJson(dir.resolve(resultFile(VAL)), result);
                writePredictions(dir.resolve("predictions_val.csv"), data, evalIdx, evalPred);
                PredictionStore.write(dir.resolve(PredictionStore.file("val")), evalIdx, evalPred.pred(), evalPred.proba());

                double seconds = (System.nanoTime() - started) / 1e9;
                state.trained(runId, scores(finalModel.trainSummary), Evaluator.scores(result), Evaluator.baselineMacroF1(result),
                        foldStats, Backtester.summary(trade), seconds);
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

    private record WalkForwardResult(int[] oofIdx, Prediction oofPred, List<Map<String, Object>> folds,
                                     ModelRun.FoldStats stats, RunModel lastFoldModel, int[] lastFoldVal) {
    }

    /**
     * 워크포워드: 폴드마다 학습 → 다음 블록 예측. 블록 예측을 모아(폴드 밖 예측) 검증 결과로 쓴다.
     * 조기 종료는 각 폴드 학습 구간의 끝 10%로 한다 (검증 블록은 학습에 쓰지 않는다).
     */
    private WalkForwardResult walkForward(ModelRun run, TrainingData data, long jobId) {
        long barMs = data.decisionMinutes * 60_000L;
        List<WalkForward.Fold> folds = WalkForward.plan(data.time, data.split, barMs, data.horizon, run.getFolds(),
                run.getFirstTrainFraction() == null ? 0.5 : run.getFirstTrainFraction(),
                run.getValidation() == RunOptions.Validation.WALK_FORWARD_ROLLING ? run.getRollingDays() : null);
        boolean proba = run.getModelType().isProbability();
        int total = Arrays.stream(folds.stream().mapToInt(f -> f.valIdx().length).toArray()).sum();
        int[] idx = new int[total];
        int[] pred = new int[total];
        double[][] pr = proba ? new double[total][] : null;
        List<Map<String, Object>> foldViews = new ArrayList<>();
        double[] f1s = new double[folds.size()];
        RunModel last = null;
        int pos = 0;
        int span = 80 / folds.size();
        for (WalkForward.Fold f : folds) {
            int base = 5 + (f.index() - 1) * span;
            int[] trainRows = f.trainIdx();
            Trained t = fit(run, data, u -> filterUnit(data, trainRows, u), u -> new int[0], jobId, base, span,
                    "폴드 " + f.index() + "/" + folds.size() + " · ");
            Prediction p = predict(t.model, data, f.valIdx(), proba);
            System.arraycopy(f.valIdx(), 0, idx, pos, f.valIdx().length);
            System.arraycopy(p.pred(), 0, pred, pos, p.pred().length);
            if (proba) {
                System.arraycopy(p.proba(), 0, pr, pos, p.proba().length);
            }
            pos += f.valIdx().length;
            last = t.model;

            int[] label = new int[f.valIdx().length];
            for (int i = 0; i < label.length; i++) {
                label[i] = data.label[f.valIdx()[i]];
            }
            Map<String, Object> s = Evaluator.summaryOnly(label, p.pred(), p.proba());
            int[] base0 = new int[label.length];
            for (int i = 0; i < label.length; i++) {
                base0[i] = t.model.majority(t.model.unitOf(data.asset[f.valIdx()[i]]));
            }
            Map<String, Object> b = Evaluator.summaryOnly(label, base0, null);
            Backtester.Summary trade = Backtester.summary(defaultBacktest(data, f.valIdx(), p, proba));
            f1s[f.index() - 1] = s.get("macroF1") == null ? 0 : (Double) s.get("macroF1");

            Map<String, Object> v = new LinkedHashMap<>();
            v.put("fold", f.index());
            v.put("trainFrom", Instant.ofEpochMilli(f.trainFrom()).toString());
            v.put("trainTo", Instant.ofEpochMilli(f.trainTo()).toString());
            v.put("valFrom", Instant.ofEpochMilli(f.valFrom()).toString());
            v.put("valTo", Instant.ofEpochMilli(f.valTo()).toString());
            v.put("trainRows", f.trainIdx().length);
            v.put("valRows", f.valIdx().length);
            v.put("macroF1", s.get("macroF1"));
            v.put("balancedAccuracy", s.get("balancedAccuracy"));
            v.put("accuracy", s.get("accuracy"));
            v.put("logLoss", s.get("logLoss"));
            v.put("baselineMacroF1", b.get("macroF1"));
            v.put("netReturnPct", trade.netReturnPct());
            v.put("maxDrawdownPct", trade.maxDrawdownPct());
            v.put("trades", trade.trades());
            foldViews.add(v);
        }
        // 폴드 밖 예측을 파일 순서(자산별 시각순)로 정렬한다
        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Integer.compare(idx[a], idx[b]));
        int[] sIdx = new int[total], sPred = new int[total];
        double[][] sPr = proba ? new double[total][] : null;
        for (int k = 0; k < total; k++) {
            sIdx[k] = idx[order[k]];
            sPred[k] = pred[order[k]];
            if (proba) {
                sPr[k] = pr[order[k]];
            }
        }
        double mean = Arrays.stream(f1s).average().orElse(0);
        double std = Math.sqrt(Arrays.stream(f1s).map(x -> (x - mean) * (x - mean)).sum() / f1s.length);
        double min = Arrays.stream(f1s).min().orElse(0);
        WalkForward.Fold lastFold = folds.get(folds.size() - 1);
        return new WalkForwardResult(sIdx, new Prediction(sPred, sPr), foldViews, new ModelRun.FoldStats(mean, std, min),
                last, lastFold.valIdx());
    }

    /** 학습 단위(자산별이면 그 자산, 통합이면 전체)에 속하는 행만 */
    private static int[] filterUnit(TrainingData data, int[] rows, int unit) {
        if (unit < 0) {
            return rows;
        }
        return Arrays.stream(rows).filter(i -> data.asset[i] == unit).toArray();
    }

    /** 탐색 기간(학습 + 검증 구간) 행 */
    private static int[] exploration(TrainingData data, int unit) {
        int[] a = data.indices(TRAIN, unit), b = data.indices(VAL, unit);
        int[] out = concat(a, b);
        Arrays.sort(out);
        return out;
    }

    /**
     * 학습 단위마다 모델을 학습한다.
     * @param trainRows 학습 단위 → 학습에 쓸 행 (자산별이면 자산 번호, 통합이면 -1 을 넘긴다)
     * @param valRows   학습 단위 → 조기 종료를 「검증 구간」으로 할 때 쓸 행
     */
    private Trained fit(ModelRun run, TrainingData data, IntFunction<int[]> trainRows, IntFunction<int[]> valRows,
                        long jobId, int progressBase, int progressSpan, String stepPrefix) {
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
            int base = progressBase + u * progressSpan / units;
            int span = Math.max(1, progressSpan / units);
            jobService.update(jobId, base, stepPrefix + unitName + " 학습 표본 준비", null);

            int[] trainIdx = sortByTime(trainRows.apply(perAsset ? u : -1), data);
            if (trainIdx.length == 0) {
                throw new IllegalStateException(stepPrefix + unitName + " 학습 행이 없습니다.");
            }
            priors[u] = ModelTrainer.prior(labels(data, trainIdx));

            int[] fitIdx = trainIdx;
            int[] stopIdx = null;
            if (run.getModelType().supportsEarlyStopping()) {
                int[] valCandidates = valRows.apply(perAsset ? u : -1);
                if (run.getEarlyStopping() == RunOptions.EarlyStopping.VALIDATION && valCandidates.length > 0) {
                    stopIdx = sample(valCandidates, STOP_ROWS, random);
                } else if (run.getEarlyStopping() != RunOptions.EarlyStopping.OFF) {
                    int cut = (int) Math.round(trainIdx.length * 0.9);
                    fitIdx = Arrays.copyOfRange(trainIdx, 0, cut);
                    stopIdx = sample(Arrays.copyOfRange(trainIdx, cut, trainIdx.length), STOP_ROWS, random);
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

            final int[] lastPct = {-1};
            BiConsumer<Integer, String> progress = (pct, step) -> {
                int p = base + Math.min(100, pct) * span / 100;
                if (p != lastPct[0]) {
                    lastPct[0] = p;
                    jobService.update(jobId, p, stepPrefix + unitName + " 학습 · " + step, null);
                }
            };
            jobService.update(jobId, base + 1, stepPrefix + unitName + " 학습 중 (" + String.format("%,d", x.length) + "행 × " + x[0].length + "열)", null);
            StringBuilder unitNotes = new StringBuilder();
            models[u] = ModelTrainer.train(run.getModelType(), run.getParams(), x, y, stopX, stopY, run.getBalance(),
                    run.getSeed(), pre, progress, unitNotes);
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

    private static Map<String, Object> validationInfo(ModelRun run, TrainingData data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scheme", run.getValidation().name());
        m.put("label", run.getValidation().getLabel());
        m.put("folds", run.getFolds());
        m.put("firstTrainFraction", run.getFirstTrainFraction());
        m.put("rollingDays", run.getRollingDays());
        m.put("purgeBars", data.horizon + 1);
        m.put("horizon", data.horizon);
        m.put("decisionMinutes", data.decisionMinutes);
        m.put("finalModel", run.getValidation().isWalkForward()
                ? "최종 테스트용 모델은 학습 + 검증 구간 전체로 같은 설정으로 다시 학습했습니다."
                : "최종 테스트용 모델은 학습 구간으로 학습한 그 모델입니다.");
        return m;
    }

    private static ModelRun.Scores scores(Map<String, Object> s) {
        return new ModelRun.Scores((Double) s.get("accuracy"), (Double) s.get("balancedAccuracy"),
                (Double) s.get("macroF1"), (Double) s.get("logLoss"));
    }

    // ===================================================== 거래 성과 (백테스트)

    /** 기본 규칙: 롱만 · 확률 모델이면 기대 수익 > 왕복 비용일 때만 매수 (아니면 예측 클래스대로) · 정답 예측 기간만큼 보유 · 시장가 */
    static Map<String, Object> defaultBacktest(TrainingData data, int[] idx, Prediction p, boolean proba) {
        Backtester.Settings s = Backtester.Settings.defaults(Backtester.DEFAULT_FEE_PCT, Backtester.DEFAULT_SLIPPAGE_PCT, data.horizon,
                proba && p.proba() != null);
        return Backtester.run(backtestInput(data, idx, p.pred(), p.proba()), s, false);
    }

    static Backtester.Input backtestInput(TrainingData data, int[] idx, int[] pred, double[][] proba) {
        int n = idx.length;
        long[] time = new long[n];
        int[] asset = new int[n];
        float[] close = new float[n], thr = new float[n];
        boolean range = data.high != null && data.low != null;
        float[] high = range ? new float[n] : null, low = range ? new float[n] : null;
        for (int i = 0; i < n; i++) {
            time[i] = data.time[idx[i]];
            asset[i] = data.asset[idx[i]];
            close[i] = data.close[idx[i]];
            thr[i] = data.threshold[idx[i]];
            if (range) {
                high[i] = data.high[idx[i]];
                low[i] = data.low[idx[i]];
            }
        }
        return new Backtester.Input(time, asset, close, high, low, pred, proba, data.assets, data.decisionMinutes * 60_000L, thr);
    }

    /** 저장한 예측으로 원하는 규칙 · 비용의 거래 성과를 계산한다 (화면 「거래 성과」 탭) */
    public Map<String, Object> backtest(long runId, String split, Backtester.Settings settings) throws IOException {
        ModelRun run = runRepository.findById(runId).orElseThrow(() -> new IllegalStateException("실험이 없습니다."));
        Path file = paths.runDir(runId).resolve(PredictionStore.file(split));
        if (!Files.exists(file)) {
            throw new IllegalStateException(split.equals("test") ? "최종 테스트를 먼저 실행하세요." : "저장된 예측이 없습니다. 실험을 다시 실행해 주세요.");
        }
        PredictionStore.Stored stored = PredictionStore.read(file);
        try (TrainingData data = new TrainingData(paths.trainingSetDir(run.getTrainingSetId()), objectMapper)) {
            Map<String, Object> out = Backtester.run(backtestInput(data, stored.idx(), stored.pred(), stored.proba()), settings, true);
            out.put("split", split);
            out.put("horizon", data.horizon);
            out.put("decisionMinutes", data.decisionMinutes);
            out.put("hasProba", stored.proba() != null);
            return out;
        }
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
                boolean proba = run.getModelType().isProbability();
                jobService.update(jobId, 20, "최종 테스트 구간 예측 (" + String.format("%,d", testIdx.length) + "행)", null);
                Prediction p = predict(model, data, testIdx, proba);
                jobService.update(jobId, 80, "평가 지표 · 거래 성과 계산", null);
                Map<String, Object> result = Evaluator.evaluate(input(model, data, testIdx, p), run.getSeed());
                Map<String, Object> trade = defaultBacktest(data, testIdx, p, proba);
                result.put("split", "test");
                result.put("evaluatedAt", Instant.now().toString());
                result.put("runCount", run.getTestRunCount() + 1);
                result.put("backtest", trade);
                writeJson(dir.resolve(resultFile(TEST)), result);
                writePredictions(dir.resolve("predictions_test.csv"), data, testIdx, p);
                PredictionStore.write(dir.resolve(PredictionStore.file("test")), testIdx, p.pred(), p.proba());
                state.tested(runId, Evaluator.scores(result), Backtester.summary(trade));
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
            case RANDOM_FOREST, GRADIENT_BOOSTING -> "트리가 그 열로 나눌 때 줄인 불순도(또는 손실)의 합입니다. 학습 구간 기준이며 값이 넓은 열이 과대평가될 수 있습니다.";
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
