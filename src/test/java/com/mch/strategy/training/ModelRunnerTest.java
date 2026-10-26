package com.mch.strategy.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mch.strategy.common.config.MlPaths;
import com.mch.strategy.job.JobService;
import com.mch.strategy.trainset.TestTrainingFiles;
import com.mch.strategy.trainset.TrainingSet;
import com.mch.strategy.trainset.TrainingSetRepository;

/** 모든 모델 종류로 학습 → 검증 평가 → 저장 → 최종 테스트 → 거래 성과까지 실제로 돌려 본다 (DB 없이). */
class ModelRunnerTest {

    @TempDir
    static Path temp;

    static MlPaths paths;

    @BeforeAll
    static void data() throws Exception {
        paths = new MlPaths(temp.toString());
        TestTrainingFiles.write(paths.trainingSetDir(1), 3000);
    }

    private record Setup(ModelRunner runner, ModelRunStateService state) {
    }

    private static Setup setup(long runId, ModelRun run) {
        ModelRunRepository runs = mock(ModelRunRepository.class);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        TrainingSet ts = mock(TrainingSet.class);
        when(ts.getId()).thenReturn(1L);
        TrainingSetRepository sets = mock(TrainingSetRepository.class);
        when(sets.findById(1L)).thenReturn(Optional.of(ts));
        ModelRunStateService state = mock(ModelRunStateService.class);
        return new Setup(new ModelRunner(runs, sets, state, mock(JobService.class), paths, new ObjectMapper()), state);
    }

    @ParameterizedTest
    @EnumSource(ModelType.class)
    void trains_validates_tests_and_backtests(ModelType type) throws Exception {
        long runId = 100 + type.ordinal();
        ModelRun run = ModelRun.create("t", 1, type, type.resolve(Map.of("ntrees", 40)),
                type == ModelType.LOGISTIC ? RunOptions.TrainUnit.POOLED : RunOptions.TrainUnit.PER_ASSET,
                type == ModelType.RANDOM_FOREST ? RunOptions.Balance.CLASS_WEIGHT : RunOptions.Balance.UNDERSAMPLE,
                RunOptions.EarlyStopping.TRAIN_TAIL, RunOptions.Validation.HOLDOUT, 1, null, null, 7, 2000, true, null);
        Setup s = setup(runId, run);

        s.runner().train(runId, 1);

        verify(s.state(), never()).fail(anyLong(), anyString());
        ArgumentCaptor<ModelRun.Scores> val = ArgumentCaptor.forClass(ModelRun.Scores.class);
        verify(s.state()).trained(eq(runId), any(), val.capture(), any(), isNull(), notNull(), anyDouble());
        Path dir = paths.runDir(runId);
        assertThat(dir.resolve("model.bin")).exists();
        assertThat(dir.resolve("pred_val.bin")).exists();
        String json = Files.readString(dir.resolve("result_val.json"));
        assertThat(json).doesNotContain("NaN").contains("\"byAsset\"", "\"importance\"", "\"train\"", "\"backtest\"", "\"validation\"");
        if (!type.isBaseline()) {
            assertThat(val.getValue().macroF1()).isGreaterThan(0.6);
        }

        s.runner().test(runId, 2);
        verify(s.state(), never()).testFailed(anyLong(), anyString());
        verify(s.state()).tested(eq(runId), any(), notNull());
        assertThat(dir.resolve("result_test.json")).exists();
        if (type == ModelType.GRADIENT_BOOSTING) {
            // 다중 분류에서 Smile size() 는 클래스 수(3)이므로, 실제 트리 수(40개 학습)를 기록해야 한다
            assertThat(json).contains("트리 40개 학습").doesNotContain("Infinity");
        }

        Map<String, Object> bt = s.runner().backtest(runId, "test",
                new Backtester.Settings(0.05, 0.02, type.isProbability(), 0.5, 0.5, 1, true, true));
        assertThat(bt).containsKeys("total", "byAsset", "curve", "monthly", "trades");
        if (type.isProbability()) {
            assertThat(bt).containsKey("sweep");
        }
    }

    @ParameterizedTest
    @EnumSource(value = RunOptions.Validation.class, names = {"WALK_FORWARD_EXPANDING", "WALK_FORWARD_ROLLING"})
    @SuppressWarnings("unchecked")
    void walk_forward_produces_fold_results_and_out_of_fold_predictions(RunOptions.Validation v) throws Exception {
        long runId = 200 + v.ordinal();
        ModelRun run = ModelRun.create("wf", 1, ModelType.GRADIENT_BOOSTING, ModelType.GRADIENT_BOOSTING.resolve(Map.of("ntrees", 30)),
                RunOptions.TrainUnit.PER_ASSET, RunOptions.Balance.NONE, RunOptions.EarlyStopping.TRAIN_TAIL,
                v, 3, 0.5, 2, 7, 2000, false, null);
        Setup s = setup(runId, run);

        s.runner().train(runId, 1);

        verify(s.state(), never()).fail(anyLong(), anyString());
        ArgumentCaptor<ModelRun.FoldStats> folds = ArgumentCaptor.forClass(ModelRun.FoldStats.class);
        verify(s.state()).trained(eq(runId), any(), any(), any(), folds.capture(), notNull(), anyDouble());
        assertThat(folds.getValue().mean()).isGreaterThan(0.5);
        assertThat(folds.getValue().min()).isLessThanOrEqualTo(folds.getValue().mean());

        Map<String, Object> result = new ObjectMapper().readValue(paths.runDir(runId).resolve("result_val.json").toFile(), Map.class);
        List<Map<String, Object>> f = (List<Map<String, Object>>) result.get("folds");
        assertThat(f).hasSize(3);
        // 폴드 밖 예측 = 탐색 기간(학습+검증 80%) 중 첫 학습 50% 이후 → 자산당 약 1200행
        assertThat(((Number) result.get("rows")).intValue()).isBetween(2200, 2500);
        // 폴드마다 학습 끝은 검증 시작보다 (N+1)봉 이상 앞
        for (Map<String, Object> fold : f) {
            assertThat(java.time.Instant.parse((String) fold.get("trainTo")))
                    .isBefore(java.time.Instant.parse((String) fold.get("valFrom")));
        }
        assertThat(paths.runDir(runId).resolve("model.bin")).exists();
    }

    @Test
    void backtest_of_test_split_requires_final_test_first() {
        long runId = 300;
        ModelRun run = ModelRun.create("bt", 1, ModelType.BASELINE_MAJORITY, Map.of(), RunOptions.TrainUnit.PER_ASSET,
                RunOptions.Balance.NONE, RunOptions.EarlyStopping.OFF, RunOptions.Validation.HOLDOUT, 1, null, null, 1, 2000, false, null);
        Setup s = setup(runId, run);
        s.runner().train(runId, 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.runner().backtest(runId, "test",
                        Backtester.Settings.defaults(0.05, 0.02, 1)))
                .hasMessageContaining("최종 테스트를 먼저");
    }
}
