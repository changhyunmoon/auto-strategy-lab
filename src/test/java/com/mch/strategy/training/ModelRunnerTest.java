package com.mch.strategy.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
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

/** 모든 모델 종류로 학습 → 검증 평가 → 저장 → 최종 테스트까지 실제로 돌려 본다 (DB 없이). */
class ModelRunnerTest {

    @TempDir
    static Path temp;

    static MlPaths paths;

    @BeforeAll
    static void data() throws Exception {
        paths = new MlPaths(temp.toString());
        TestTrainingFiles.write(paths.trainingSetDir(1), 3000);
    }

    @ParameterizedTest
    @EnumSource(ModelType.class)
    void trains_validates_and_tests(ModelType type) throws Exception {
        long runId = 100 + type.ordinal();
        ModelRun run = ModelRun.create("t", 1, type, type.resolve(Map.of("ntrees", 40)),
                type == ModelType.LOGISTIC ? RunOptions.TrainUnit.POOLED : RunOptions.TrainUnit.PER_ASSET,
                type == ModelType.RANDOM_FOREST ? RunOptions.Balance.CLASS_WEIGHT : RunOptions.Balance.UNDERSAMPLE,
                RunOptions.EarlyStopping.TRAIN_TAIL, 7, 2000, true, null);
        ModelRunRepository runs = mock(ModelRunRepository.class);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        TrainingSet ts = mock(TrainingSet.class);
        when(ts.getId()).thenReturn(1L);
        when(ts.getThresholdPct()).thenReturn(new BigDecimal("0.3"));
        TrainingSetRepository sets = mock(TrainingSetRepository.class);
        when(sets.findById(1L)).thenReturn(Optional.of(ts));
        ModelRunStateService state = mock(ModelRunStateService.class);
        JobService jobs = mock(JobService.class);
        ModelRunner runner = new ModelRunner(runs, sets, state, jobs, paths, new ObjectMapper());

        runner.train(runId, 1);

        verify(state, never()).fail(anyLong(), anyString());
        ArgumentCaptor<ModelRun.Scores> val = ArgumentCaptor.forClass(ModelRun.Scores.class);
        verify(state).trained(eq(runId), any(), val.capture(), any(), anyDouble());
        Path dir = paths.runDir(runId);
        assertThat(dir.resolve("model.bin")).exists();
        assertThat(dir.resolve("predictions_val.csv")).exists();
        String json = Files.readString(dir.resolve("result_val.json"));
        assertThat(json).doesNotContain("NaN").contains("\"byAsset\"", "\"importance\"", "\"train\"");

        // 신호가 강한 데이터라 학습 모델은 최빈 클래스보다 확실히 나아야 한다
        if (!type.isBaseline()) {
            assertThat(val.getValue().macroF1()).isGreaterThan(0.6);
        }

        runner.test(runId, 2);
        verify(state, never()).testFailed(anyLong(), anyString());
        verify(state).tested(eq(runId), any());
        assertThat(dir.resolve("result_test.json")).exists();
        if (type == ModelType.GRADIENT_BOOSTING) {
            // 다중 분류에서 Smile size() 는 클래스 수(3)이므로, 실제 트리 수(40개 학습)를 기록해야 한다
            assertThat(json).contains("트리 40개 학습").doesNotContain("Infinity");
        }
    }
}
