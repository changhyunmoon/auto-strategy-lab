package com.mch.strategy.training;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.common.TimeUtils;
import com.mch.strategy.common.config.MlPaths;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.training.ModelRunViews.Choice;
import com.mch.strategy.training.ModelRunViews.Detail;
import com.mch.strategy.training.ModelRunViews.ModelOption;
import com.mch.strategy.training.ModelRunViews.ParamView;
import com.mch.strategy.training.ModelRunViews.Summary;
import com.mch.strategy.trainset.TrainingSet;
import com.mch.strategy.trainset.TrainingSetRepository;

import lombok.RequiredArgsConstructor;

/** 실험 목록 · 상세 · 결과 파일 조회, 모델 학습 화면의 선택지 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModelRunQueryService {

    private final ModelRunRepository repository;
    private final TrainingSetRepository trainingSetRepository;
    private final AssetRepository assetRepository;
    private final JobRepository jobRepository;
    private final MlPaths paths;

    public List<Summary> list(Long trainingSetId) {
        List<ModelRun> runs = trainingSetId == null ? repository.findAllByOrderByIdDesc()
                : repository.findByTrainingSetIdOrderByIdDesc(trainingSetId);
        Map<Long, String> setNames = trainingSetRepository.findAll().stream()
                .collect(Collectors.toMap(TrainingSet::getId, TrainingSet::getName));
        return runs.stream().map(r -> summary(r, setNames.getOrDefault(r.getTrainingSetId(), "(삭제됨)"))).toList();
    }

    public ModelRun get(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("실험이 없습니다: " + id));
    }

    public Detail detail(long id) {
        ModelRun r = get(id);
        TrainingSet ts = trainingSetRepository.findById(r.getTrainingSetId()).orElse(null);
        Map<Long, String> codes = assetRepository.findAll().stream().collect(Collectors.toMap(Asset::getId, Asset::getCode));
        Map<String, ModelType.ParamSpec> specs = r.getModelType().getParams().stream()
                .collect(Collectors.toMap(ModelType.ParamSpec::key, Function.identity()));
        List<ParamView> params = r.getParams().entrySet().stream()
                .map(e -> new ParamView(specs.containsKey(e.getKey()) ? specs.get(e.getKey()).label() : e.getKey(), String.valueOf(e.getValue())))
                .toList();
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("trainingSetId", r.getTrainingSetId());
        copy.put("model", r.getModelType().name());
        copy.put("params", r.getParams());
        copy.put("trainUnit", r.getTrainUnit().name());
        copy.put("balance", r.getBalance().name());
        copy.put("earlyStopping", r.getEarlyStopping().name());
        copy.put("seed", r.getSeed());
        copy.put("validation", r.getValidation().name());
        copy.put("folds", r.getFolds());
        copy.put("firstTrainFraction", r.getFirstTrainFraction());
        copy.put("rollingDays", r.getRollingDays());
        copy.put("maxTrainRows", r.getMaxTrainRows());
        copy.put("permutationImportance", r.isPermutationImportance());
        return new Detail(
                summary(r, ts == null ? "(삭제됨)" : ts.getName()),
                r.getModelType().getDescription(),
                r.getModelType().isProbability(),
                params,
                r.getTrainUnit().getDescription(),
                r.getBalance().getDescription(),
                r.getModelType().supportsEarlyStopping() ? r.getEarlyStopping().getLabel() : "해당 없음",
                validationText(r),
                r.getValidation().getDescription(),
                ts == null ? "" : ts.labelSpec().text(Intervals.label(ts.getDecisionInterval())),
                ts == null ? 1 : ts.labelSpec().horizon(),
                r.isPermutationImportance(),
                r.getMemo() == null ? "" : r.getMemo(),
                ts == null ? "" : Intervals.label(ts.getDecisionInterval())
                        + (ts.getExtraIntervals().isEmpty() ? "" : " (+" + String.join("·", ts.getExtraIntervals().stream().map(Intervals::label).toList()) + " 지표)"),
                ts == null ? "" : ts.getThresholdPct().stripTrailingZeros().toPlainString(),
                ts == null ? List.of() : ts.getAssetIds().stream().map(a -> codes.getOrDefault(a, "?")).toList(),
                ts == null ? "" : ts.getTrainFrom() + " ~ " + ts.getTrainTo(),
                ts == null ? "" : ts.getValFrom() + " ~ " + ts.getValTo(),
                ts == null ? "" : ts.getTestFrom() + " ~ " + ts.getTestTo(),
                copy);
    }

    /** 결과 JSON 파일 내용 그대로 (split = val · test). 없으면 null */
    public String result(long id, String split) {
        get(id);
        int s = switch (split) {
            case "val" -> ModelRunner.VAL;
            case "test" -> ModelRunner.TEST;
            default -> throw ApiException.badRequest("split 은 val 또는 test 입니다.");
        };
        Path file = paths.runDir(id).resolve(ModelRunner.resultFile(s));
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("결과 파일을 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    public Path predictionsFile(long id, String split) {
        get(id);
        if (!split.equals("val") && !split.equals("test")) {
            throw ApiException.badRequest("split 은 val 또는 test 입니다.");
        }
        Path file = paths.runDir(id).resolve("predictions_" + split + ".csv");
        if (!Files.exists(file)) {
            throw ApiException.notFound("예측 파일이 없습니다.");
        }
        return file;
    }

    public List<ModelOption> modelOptions() {
        return Arrays.stream(ModelType.values()).map(t -> new ModelOption(t.name(), t.getFamily(), t.getLabel(),
                t.getDescription(), t.isProbability(), t.isBaseline(), t.supportsEarlyStopping(), t.supportsClassWeight(),
                t.getParams())).toList();
    }

    public List<Choice> trainUnits() {
        return Arrays.stream(RunOptions.TrainUnit.values()).map(v -> new Choice(v.name(), v.getLabel(), v.getDescription())).toList();
    }

    public List<Choice> balances() {
        return Arrays.stream(RunOptions.Balance.values()).map(v -> new Choice(v.name(), v.getLabel(), v.getDescription())).toList();
    }

    public List<Choice> validations() {
        return Arrays.stream(RunOptions.Validation.values()).map(v -> new Choice(v.name(), v.getLabel(), v.getDescription())).toList();
    }

    private static String validationText(ModelRun r) {
        if (!r.getValidation().isWalkForward()) {
            return r.getValidation().getLabel();
        }
        String s = r.getValidation().getLabel() + " · 폴드 " + r.getFolds() + " · 첫 학습 "
                + Math.round((r.getFirstTrainFraction() == null ? 0.5 : r.getFirstTrainFraction()) * 100) + "%";
        return r.getRollingDays() == null ? s : s + " · 학습 창 " + r.getRollingDays() + "일";
    }

    public List<Choice> earlyStoppings() {
        return Arrays.stream(RunOptions.EarlyStopping.values()).map(v -> new Choice(v.name(), v.getLabel(), v.getDescription())).toList();
    }

    Summary summary(ModelRun r, String setName) {
        Job train = jobRepository.findFirstByJobTypeAndTargetIdOrderByIdDesc(Job.TYPE_MODEL_TRAIN, r.getId()).orElse(null);
        Job test = jobRepository.findFirstByJobTypeAndTargetIdOrderByIdDesc(Job.TYPE_MODEL_TEST, r.getId()).orElse(null);
        return new Summary(
                r.getId(), r.getName(), r.getTrainingSetId(), setName,
                r.getModelType().name(), r.getModelType().getLabel(), r.getModelType().getFamily(),
                r.getStatus().name(), r.getStatus().getLabel(), r.isBusy(),
                r.getTrainUnit().getLabel(), r.getBalance().getLabel(), r.getParams(), r.getSeed(), r.getMaxTrainRows(),
                r.getTrainMacroF1(), r.getValAccuracy(), r.getValBalancedAccuracy(), r.getValMacroF1(), r.getValLogLoss(),
                r.getValBaselineMacroF1(),
                r.getValidation().getLabel(), r.getFolds(), r.getValFoldF1Mean(), r.getValFoldF1Std(), r.getValFoldF1Min(),
                r.getValNetReturn(), r.getValMaxDrawdown(), r.getValTrades(),
                r.getTestStatus().name(), r.getTestAccuracy(), r.getTestBalancedAccuracy(), r.getTestMacroF1(), r.getTestLogLoss(),
                r.getTestNetReturn(), r.getTestMaxDrawdown(), r.getTestTrades(),
                r.getTestRunCount(), r.getTestEvaluatedAt() == null ? "" : TimeUtils.toKstText(r.getTestEvaluatedAt()),
                r.getTrainSeconds(), TimeUtils.toKstText(r.getCreatedAt()),
                train == null ? null : train.getId(), train == null ? 0 : train.getProgress(),
                train == null || train.getStep() == null ? "" : train.getStep(),
                test == null ? null : test.getId(), test == null ? 0 : test.getProgress(),
                test == null || test.getStep() == null ? "" : test.getStep(),
                r.getMessage() == null ? "" : r.getMessage());
    }
}
