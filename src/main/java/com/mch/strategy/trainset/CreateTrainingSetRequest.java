package com.mch.strategy.trainset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 학습 데이터 만들기 요청. 날짜는 모두 UTC 날짜이며 끝 날짜 하루 전체를 포함한다.
 * 학습 → 검증 → 최종 테스트 순서로 겹치지 않아야 한다.
 */
public record CreateTrainingSetRequest(
        @NotBlank(message = "이름을 입력하세요.") @Size(max = 100) String name,
        @NotNull(message = "기준 데이터세트를 고르세요.") Long datasetId,
        @NotBlank(message = "판단 간격을 고르세요.") String decisionInterval,
        List<String> extraIntervals,
        @NotEmpty(message = "종목을 하나 이상 고르세요.") List<Long> assetIds,
        @NotEmpty(message = "지표 그룹을 하나 이상 고르세요.") List<String> featureGroups,
        boolean includeBarColumns,
        @NotNull(message = "정답 기준 %를 고르세요.") BigDecimal thresholdPct,
        @NotNull(message = "학습 시작일을 고르세요.") LocalDate trainFrom,
        @NotNull(message = "학습 종료일을 고르세요.") LocalDate trainTo,
        @NotNull(message = "검증 시작일을 고르세요.") LocalDate valFrom,
        @NotNull(message = "검증 종료일을 고르세요.") LocalDate valTo,
        @NotNull(message = "테스트 시작일을 고르세요.") LocalDate testFrom,
        @NotNull(message = "테스트 종료일을 고르세요.") LocalDate testTo
) {
}
