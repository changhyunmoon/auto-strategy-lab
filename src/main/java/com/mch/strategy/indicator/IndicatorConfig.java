package com.mch.strategy.indicator;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 기술 지표 생성 화면의 설정 전체. 여섯 지표를 한 번에 담는다.
 * 기간 단위는 「완료된 1분봉 개수」다 (20 = 최근 20분). 목록 값은 {@link #normalized()} 에서 중복 제거 · 정렬한다.
 * 화면에서 꺼진 지표도 입력값은 그대로 보존해 저장한다 (다시 켜면 이어서 쓴다).
 */
public record IndicatorConfig(
        Volume volume,
        Ma ma,
        Rsi rsi,
        Macd macd,
        Atr atr,
        Bb bb
) {

    /** 거래량: 기간 집합 S, 평균 · RVOL · VO · MFI · CMF · OBV 묶음 */
    public record Volume(boolean enabled, List<Integer> periods,
                         boolean sma, boolean rvol, boolean vo, boolean mfi, boolean cmf, boolean obv) {
        Volume normalized() {
            return new Volume(enabled, ints(periods), sma, rvol, vo, mfi, cmf, obv);
        }
    }

    /** 이동평균선: 종류(SMA·EMA·WMA·VWMA), 기간 S, 기울기 간격 L, 두 선 비교 범위, 다섯 묶음 */
    public record Ma(boolean enabled, List<String> types, List<Integer> periods, List<Integer> slopeLags,
                     String pairScope,
                     boolean raw, boolean distance, boolean slope, boolean gap, boolean above) {
        public static final List<String> TYPE_ORDER = List.of("SMA", "EMA", "WMA", "VWMA");
        public static final String SAME_TYPE = "SAME_TYPE";
        public static final String ALL = "ALL";

        Ma normalized() {
            List<String> t = TYPE_ORDER.stream().filter(x -> types != null && types.contains(x)).toList();
            return new Ma(enabled, t, ints(periods), ints(slopeLags), ALL.equals(pairScope) ? ALL : SAME_TYPE,
                    raw, distance, slope, gap, above);
        }
    }

    /** RSI 기준값 한 쌍 (하단, 상단). 0 ≤ 하단 < 상단 ≤ 100 */
    public record Band(double lower, double upper) {
    }

    /** RSI: 기간 S, 변화량 간격 L, 기준값 쌍, StochRSI 범위 기간 R, 일곱 묶음 */
    public record Rsi(boolean enabled, List<Integer> periods, List<Integer> deltaLags, List<Band> bands,
                      List<Integer> stochRanges,
                      boolean raw, boolean center, boolean delta, boolean pairGap, boolean pairAbove,
                      boolean zones, boolean stoch) {
        Rsi normalized() {
            List<Band> b = new ArrayList<>();
            if (bands != null) {
                for (Band band : bands) {
                    if (band != null && b.stream().noneMatch(x -> x.lower() == band.lower() && x.upper() == band.upper())) {
                        b.add(band);
                    }
                }
            }
            b.sort((x, y) -> x.lower() != y.lower() ? Double.compare(x.lower(), y.lower()) : Double.compare(x.upper(), y.upper()));
            return new Rsi(enabled, ints(periods), ints(deltaLags), b, ints(stochRanges),
                    raw, center, delta, pairGap, pairAbove, zones, stoch);
        }
    }

    /** MACD: 빠른 F · 느린 S · 신호 G · 변화량 간격 L, 다섯 묶음 */
    public record Macd(boolean enabled, List<Integer> fast, List<Integer> slow, List<Integer> signal,
                       List<Integer> deltaLags,
                       boolean base, boolean pct, boolean delta, boolean position, boolean cross) {
        Macd normalized() {
            return new Macd(enabled, ints(fast), ints(slow), ints(signal), ints(deltaLags),
                    base, pct, delta, position, cross);
        }
    }

    /** ATR: 기간 S, 변화량 간격 L, 일곱 묶음 */
    public record Atr(boolean enabled, List<Integer> periods, List<Integer> deltaLags,
                      boolean tr, boolean atr, boolean atrPct, boolean trToAtr, boolean delta,
                      boolean pairRatio, boolean pairAbove) {
        Atr normalized() {
            return new Atr(enabled, ints(periods), ints(deltaLags), tr, atr, atrPct, trToAtr, delta, pairRatio, pairAbove);
        }
    }

    /** 볼린저 밴드: 기간 N, 표준편차 배수 K, 일곱 묶음 */
    public record Bb(boolean enabled, List<Integer> periods, List<Double> multipliers,
                     boolean mid, boolean std, boolean z, boolean bands, boolean width, boolean percentB,
                     boolean flags) {
        Bb normalized() {
            List<Double> k = new ArrayList<>(new TreeSet<>(multipliers == null ? List.of()
                    : multipliers.stream().filter(v -> v != null).toList()));
            return new Bb(enabled, ints(periods), k, mid, std, z, bands, width, percentB, flags);
        }
    }

    /** 빈 항목을 채우고 목록을 중복 제거 · 정렬한 사본 */
    public IndicatorConfig normalized() {
        IndicatorConfig d = defaults();
        return new IndicatorConfig(
                (volume == null ? d.volume : volume).normalized(),
                (ma == null ? d.ma : ma).normalized(),
                (rsi == null ? d.rsi : rsi).normalized(),
                (macd == null ? d.macd : macd).normalized(),
                (atr == null ? d.atr : atr).normalized(),
                (bb == null ? d.bb : bb).normalized());
    }

    /** 노션 문서의 예시값. 확정된 기본값이 아니라 화면을 처음 열었을 때의 시작값이다. */
    public static IndicatorConfig defaults() {
        return new IndicatorConfig(
                new Volume(true, List.of(5, 10, 20, 60), true, true, true, true, true, true),
                new Ma(true, List.of("SMA", "EMA", "WMA", "VWMA"), List.of(5, 10, 20, 60), List.of(1, 5),
                        Ma.SAME_TYPE, true, true, true, true, true),
                new Rsi(true, List.of(5, 10, 20, 60), List.of(1, 5), List.of(new Band(30, 70)), List.of(10, 20),
                        true, true, true, true, true, true, false),
                new Macd(true, List.of(5, 12), List.of(20, 26, 60), List.of(5, 9), List.of(1, 5),
                        true, true, true, true, true),
                new Atr(true, List.of(5, 10, 20, 60), List.of(1, 5), true, true, true, true, true, true, true),
                new Bb(true, List.of(10, 20, 60), List.of(1.5, 2.0), true, true, true, true, true, true, true));
    }

    /** null · 중복을 빼고 오름차순. 0 · 음수는 남겨 두고 검사 단계에서 오류로 알린다. */
    private static List<Integer> ints(List<Integer> values) {
        if (values == null) {
            return List.of();
        }
        return new ArrayList<>(new TreeSet<>(values.stream().filter(v -> v != null).toList()));
    }
}
