package com.mch.strategy.candle;

import java.util.Arrays;

import com.mch.strategy.collect.CandleSeries;

/**
 * 1분봉을 N분봉으로 묶는다. 봉 시작 시각은 UTC 기준 N분 경계(예: 15분봉은 :00 :15 :30 :45).
 * 1분봉이 비어 있는 분은 있는 봉만으로 묶는다.
 */
public final class CandleAggregator {

    private CandleAggregator() {
    }

    public static CandleSeries aggregate(CandleSeries s, int minutes) {
        if (minutes == 1 || s.size() == 0) {
            return s;
        }
        long bucketMs = minutes * 60_000L;
        int n = s.size();
        long[] time = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n];
        double[] v = new double[n], qv = new double[n], tc = new double[n], tb = new double[n];

        int k = -1;
        long currentBucket = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long bucket = Math.floorDiv(s.time()[i], bucketMs) * bucketMs;
            if (bucket != currentBucket) {
                k++;
                currentBucket = bucket;
                time[k] = bucket;
                o[k] = s.open()[i];
                h[k] = s.high()[i];
                l[k] = s.low()[i];
                v[k] = 0;
                qv[k] = 0;
                tc[k] = Double.NaN;
                tb[k] = Double.NaN;
            }
            h[k] = Math.max(h[k], s.high()[i]);
            l[k] = Math.min(l[k], s.low()[i]);
            c[k] = s.close()[i];
            v[k] += s.volume()[i];
            qv[k] += s.quoteVolume()[i];
            tc[k] = nanSum(tc[k], s.tradeCount()[i]);
            tb[k] = nanSum(tb[k], s.takerBuyVolume()[i]);
        }
        int size = k + 1;
        return new CandleSeries(Arrays.copyOf(time, size), Arrays.copyOf(o, size), Arrays.copyOf(h, size),
                Arrays.copyOf(l, size), Arrays.copyOf(c, size), Arrays.copyOf(v, size), Arrays.copyOf(qv, size),
                Arrays.copyOf(tc, size), Arrays.copyOf(tb, size));
    }

    /** 둘 다 NaN 이면 NaN, 하나만 값이 있으면 그 값. */
    private static double nanSum(double acc, double x) {
        if (Double.isNaN(x)) {
            return acc;
        }
        return Double.isNaN(acc) ? x : acc + x;
    }
}
