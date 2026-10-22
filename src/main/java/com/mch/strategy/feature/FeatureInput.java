package com.mch.strategy.feature;

/**
 * 한 자산 · 한 봉 간격의 시간순 봉 배열. 인덱스 i 는 모든 배열에서 같은 봉이다.
 * 지표는 i 번째 값을 계산할 때 0~i 번째 봉만 사용해야 한다 (미래 정보 금지).
 *
 * <ul>
 *   <li>open~quoteVolume: 실행 시장(업비트, 업비트를 고르지 않았으면 바이낸스)의 봉</li>
 *   <li>ref*: 같은 자산의 바이낸스 봉을 같은 시각에 맞춘 값. 바이낸스를 고르지 않았으면 모두 NaN</li>
 *   <li>otherClose: 다른 자산(BTC ↔ ETH)의 실행 시장 종가. 한 자산만 골랐으면 모두 NaN</li>
 * </ul>
 */
public record FeatureInput(
        long[] time,
        double[] open,
        double[] high,
        double[] low,
        double[] close,
        double[] volume,
        double[] quoteVolume,
        double[] refClose,
        double[] refVolume,
        double[] refTakerBuy,
        double[] refTradeCount,
        double[] otherClose,
        int intervalMinutes
) {
    public int size() {
        return close.length;
    }
}
