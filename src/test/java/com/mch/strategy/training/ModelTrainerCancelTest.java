package com.mch.strategy.training;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.mch.strategy.job.JobCancels;

/** 트리 모델 학습 도중 「중지」 하면 남은 트리를 만들지 않고 멈춘다 */
class ModelTrainerCancelTest {

    @Test
    void random_forest_and_boosting_stop_when_progress_reports_cancel() {
        Random r = new Random(1);
        int n = 3000;
        double[][] x = new double[n][5];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 5; j++) {
                x[i][j] = r.nextGaussian();
            }
            y[i] = r.nextInt(3);
        }
        for (ModelType type : new ModelType[]{ModelType.RANDOM_FOREST, ModelType.GRADIENT_BOOSTING}) {
            Map<String, Object> params = type.resolve(Map.of("ntrees", 1000));
            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> ModelTrainer.train(type, params, x, y, null, null, RunOptions.Balance.NONE, 1,
                    Preprocessor.fit(x), (pct, step) -> {
                        if (calls.incrementAndGet() >= 3) {
                            throw new JobCancels.CancelledException();
                        }
                    }, new StringBuilder()))
                    .isInstanceOf(JobCancels.CancelledException.class);
        }
    }
}
