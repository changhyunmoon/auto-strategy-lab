package com.mch.strategy.training;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 거래 성과 탭의 「계산」 요청. % 값은 퍼센트 단위, 확률 기준은 0~1.
 * orderType 을 비우면 시장가, limitWaitBars 를 비우면 1봉.
 */
public record BacktestRequest(
        @NotBlank String split,
        @DecimalMin(value = "0", message = "수수료는 0 이상이어야 합니다.") @DecimalMax(value = "1", message = "수수료는 1% 이하여야 합니다.") double feePct,
        @DecimalMin(value = "0", message = "슬리피지는 0 이상이어야 합니다.") @DecimalMax(value = "1", message = "슬리피지는 1% 이하여야 합니다.") double slippagePct,
        boolean useProbability,
        @DecimalMin("0") @DecimalMax("1") double upThreshold,
        @DecimalMin("0") @DecimalMax("1") double downThreshold,
        @Min(value = 1, message = "보유 봉 수는 1 이상이어야 합니다.") @Max(value = 1440, message = "보유 봉 수는 1440 이하여야 합니다.") int holdBars,
        boolean extendOnSignal,
        @Pattern(regexp = "MARKET|LIMIT", message = "주문 방식은 MARKET 또는 LIMIT 입니다.") String orderType,
        @Min(value = 1, message = "지정가 대기 봉 수는 1 이상이어야 합니다.") @Max(value = 60, message = "지정가 대기 봉 수는 60 이하여야 합니다.") Integer limitWaitBars
) {
    public Backtester.Settings settings() {
        return new Backtester.Settings(feePct, slippagePct, useProbability, upThreshold, downThreshold, holdBars, extendOnSignal, true,
                orderType == null ? Backtester.MARKET : orderType,
                limitWaitBars == null ? Backtester.DEFAULT_LIMIT_WAIT_BARS : limitWaitBars);
    }
}
