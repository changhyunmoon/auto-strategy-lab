package com.mch.strategy.training;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 모델 학습 화면의 「학습 시작」 요청. 고른 모델마다 실험이 하나씩 만들어지고 차례로 실행된다.
 */
public record CreateRunsRequest(
        @NotNull(message = "학습 데이터를 고르세요.") Long trainingSetId,
        @NotBlank(message = "실험 이름을 입력하세요.") @Size(max = 100) String name,
        @NotEmpty(message = "모델을 하나 이상 고르세요.") List<@Valid ModelChoice> models,
        @NotNull RunOptions.TrainUnit trainUnit,
        @NotNull RunOptions.Balance balance,
        @NotNull RunOptions.EarlyStopping earlyStopping,
        RunOptions.Validation validation,
        @Min(value = 2, message = "폴드 수는 2 이상이어야 합니다.") @Max(value = 12, message = "폴드 수는 12 이하여야 합니다.") Integer folds,
        Double firstTrainFraction,
        @Min(value = 1, message = "학습 창은 1일 이상이어야 합니다.") Integer rollingDays,
        @Min(0) @Max(Integer.MAX_VALUE) int seed,
        @Min(value = 1000, message = "학습 표본 수 상한은 1,000 이상이어야 합니다.")
        @Max(value = 5_000_000, message = "학습 표본 수 상한은 5,000,000 이하여야 합니다.") int maxTrainRows,
        boolean permutationImportance,
        @Size(max = 2000) String memo
) {

    public RunOptions.Validation validationOrDefault() {
        return validation == null ? RunOptions.Validation.HOLDOUT : validation;
    }

    public record ModelChoice(@NotNull ModelType type, Map<String, Object> params) {
    }
}
