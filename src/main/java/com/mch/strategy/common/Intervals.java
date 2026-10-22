package com.mch.strategy.common;

import java.util.List;

/** 봉 간격 표기 "1m" · "5m" · "15m" 와 분 단위 숫자를 오간다. */
public final class Intervals {

    public static final List<String> SUPPORTED = List.of("1m", "5m", "15m");

    private Intervals() {
    }

    public static int minutes(String interval) {
        if (!SUPPORTED.contains(interval)) {
            throw ApiException.badRequest("지원하지 않는 봉 간격: " + interval);
        }
        return Integer.parseInt(interval.substring(0, interval.length() - 1));
    }

    public static String label(String interval) {
        return minutes(interval) + "분";
    }
}
