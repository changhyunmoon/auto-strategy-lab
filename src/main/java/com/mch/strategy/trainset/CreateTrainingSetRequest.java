package com.mch.strategy.trainset;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 학습 데이터 만들기 요청. 날짜는 모두 UTC 날짜이며 끝 날짜 하루 전체를 포함한다.
 * 학습 → 검증 → 최종 테스트 순서로 겹치지 않아야 한다.
 * 정답: labelMethod(N봉 뒤 수익률 / 목표가·손절가) · horizon(N봉) · thresholdMode(고정 / 변동성 / 비용) 와 그 세부 값.
 */
public record CreateTrainingSetRequest(
        @NotBlank(message = "이름을 입력하세요.") @Size(max = 100) String name,
        @NotNull(message = "기준 데이터세트를 고르세요.") Long datasetId,
        @NotBlank(message = "판단 간격을 고르세요.") String decisionInterval,
        List<String> extraIntervals,
        @NotEmpty(message = "종목을 하나 이상 고르세요.") List<Long> assetIds,
        @NotEmpty(message = "지표 그룹을 하나 이상 고르세요.") List<String> featureGroups,
        boolean includeBarColumns,
        String labelMethod,
        Integer horizon,
        String thresholdMode,
        Double thresholdPct,
        Double volK,
        Integer volWindow,
        Double feePct,
        Double slippagePct,
        Double marginPct,
        @NotNull(message = "학습 시작일을 고르세요.") LocalDate trainFrom,
        @NotNull(message = "학습 종료일을 고르세요.") LocalDate trainTo,
        @NotNull(message = "검증 시작일을 고르세요.") LocalDate valFrom,
        @NotNull(message = "검증 종료일을 고르세요.") LocalDate valTo,
        @NotNull(message = "테스트 시작일을 고르세요.") LocalDate testFrom,
        @NotNull(message = "테스트 종료일을 고르세요.") LocalDate testTo
) {

    /** 빠진 값은 기본값(다음 1봉 · 고정 0.3% · 업비트 수수료 0.05%)으로 채운다 */
    public LabelSpec labelSpec() {
        return new LabelSpec(
                labelMethod == null ? LabelSpec.FORWARD_RETURN : labelMethod,
                horizon == null ? 1 : horizon,
                thresholdMode == null ? LabelSpec.FIXED : thresholdMode,
                thresholdPct == null ? 0.3 : thresholdPct,
                volK == null ? 1.0 : volK,
                volWindow == null ? 60 : volWindow,
                feePct == null ? 0.05 : feePct,
                slippagePct == null ? 0.02 : slippagePct,
                marginPct == null ? 0.0 : marginPct);
    }
}
