package com.mch.strategy.indicator;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 끊김 없는 1분봉 한 구간의 원본 값과, 여러 열이 함께 쓰는 중간 계산(SMA · EMA · RSI …)의 저장소.
 * 같은 구간 안에서 sma_20 을 이동평균선 · 볼린저 밴드가 모두 써도 한 번만 계산한다.
 */
public final class Ctx {

    public final double[] open;
    public final double[] high;
    public final double[] low;
    public final double[] close;
    public final double[] volume;
    private final Map<String, double[]> cache = new HashMap<>();

    public Ctx(double[] open, double[] high, double[] low, double[] close, double[] volume) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public int size() {
        return close.length;
    }

    /** key 로 저장된 값이 있으면 그대로, 없으면 계산해 저장한다. (계산 안에서 다시 get 을 불러도 된다) */
    public double[] get(String key, Supplier<double[]> supplier) {
        double[] v = cache.get(key);
        if (v == null) {
            v = supplier.get();
            cache.put(key, v);
        }
        return v;
    }

    /** 종가 이동평균선. type = SMA · EMA · WMA · VWMA */
    public double[] ma(String type, int n) {
        return switch (type) {
            case "SMA" -> get("sma:" + n, () -> Ta.sma(close, n));
            case "EMA" -> get("ema:" + n, () -> Ta.ema(close, n));
            case "WMA" -> get("wma:" + n, () -> Ta.wma(close, n));
            case "VWMA" -> get("vwma:" + n, () -> Ta.vwma(close, volume, n));
            default -> throw new IllegalArgumentException("이동평균 종류: " + type);
        };
    }

    public double[] rsi(int n) {
        return get("rsi:" + n, () -> Ta.rsi(close, n));
    }

    public double[] tr() {
        return get("tr", () -> Ta.trueRange(high, low, close));
    }

    public double[] atr(int n) {
        return get("atr:" + n, () -> Ta.atr(tr(), n));
    }

    public double[] std(int n) {
        return get("std:" + n, () -> Ta.stdev(close, n));
    }

    /** MACD 선 = EMA_f − EMA_s */
    public double[] macd(int f, int s) {
        return get("macd:" + f + ":" + s, () -> Ta.minus(ma("EMA", f), ma("EMA", s)));
    }

    /** 신호선 = MACD 선의 EMA_g */
    public double[] signal(int f, int s, int g) {
        return get("signal:" + f + ":" + s + ":" + g, () -> Ta.ema(macd(f, s), g));
    }

    /** 히스토그램 = MACD − 신호선 */
    public double[] hist(int f, int s, int g) {
        return get("hist:" + f + ":" + s + ":" + g, () -> Ta.minus(macd(f, s), signal(f, s, g)));
    }
}
