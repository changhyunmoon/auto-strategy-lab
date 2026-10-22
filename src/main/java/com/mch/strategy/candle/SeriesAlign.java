package com.mch.strategy.candle;

import java.util.Arrays;

/** 다른 시장(바이낸스·다른 자산)의 값을 기준 봉 시각에 맞춘다. 같은 시각 봉이 없으면 NaN. */
public final class SeriesAlign {

    private SeriesAlign() {
    }

    /** baseTime, otherTime 모두 오름차순이어야 한다. */
    public static double[] align(long[] baseTime, long[] otherTime, double[] otherValues) {
        double[] out = new double[baseTime.length];
        Arrays.fill(out, Double.NaN);
        int j = 0;
        for (int i = 0; i < baseTime.length; i++) {
            while (j < otherTime.length && otherTime[j] < baseTime[i]) {
                j++;
            }
            if (j < otherTime.length && otherTime[j] == baseTime[i]) {
                out[i] = otherValues[j];
            }
        }
        return out;
    }

    public static double[] nanArray(int n) {
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        return out;
    }
}
