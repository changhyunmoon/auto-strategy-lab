package com.mch.strategy.collect;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 원본 데이터 받기 화면의 「받기」 요청. 종목 × 거래소 마다 1분봉을 받아 candle_1m 에 저장한다.
 * from·to 는 UTC 날짜이며 to 날짜 하루 전체를 포함한다.
 * includeWarmup 이면 데이터세트를 만들 때 지표 계산에 필요한 앞쪽 기간(워밍업)까지 함께 받는다.
 */
public record RawCollectRequest(
        @NotEmpty(message = "종목을 하나 이상 고르세요.") List<Long> assetIds,
        @NotEmpty(message = "거래소를 하나 이상 고르세요.") List<String> exchanges,
        @NotNull(message = "시작일을 고르세요.") LocalDate from,
        @NotNull(message = "종료일을 고르세요.") LocalDate to,
        boolean includeWarmup
) {
}
