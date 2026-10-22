package com.mch.strategy.collect;

/**
 * 시간순 봉 배열 묶음. 인덱스 i 는 모든 배열에서 같은 봉이다.
 * time 은 봉 시작 시각(UTC epoch 밀리초). 값이 없는 칸은 NaN.
 */
public record CandleSeries(
        long[] time,
        double[] open,
        double[] high,
        double[] low,
        double[] close,
        double[] volume,
        double[] quoteVolume,
        double[] tradeCount,
        double[] takerBuyVolume
) {
    public int size() {
        return time.length;
    }

    public static CandleSeries empty() {
        return new CandleSeries(new long[0], new double[0], new double[0], new double[0], new double[0],
                new double[0], new double[0], new double[0], new double[0]);
    }
}
