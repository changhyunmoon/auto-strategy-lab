package com.mch.strategy.trainset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mch.strategy.common.ApiException;

/**
 * 정답(예측 목표) 정의.
 * <ul>
 *   <li>method: FORWARD_RETURN = N봉 뒤 종가 수익률로 상승·중립·하락 /
 *       TRIPLE_BARRIER = N봉 안에 +기준(목표가)과 −기준(손절가) 중 먼저 닿은 쪽 (둘 다 아니면 중립)</li>
 *   <li>mode: FIXED = 고정 % / VOLATILITY = 최근 변동성 × k × √N / COST = 왕복 수수료 + 슬리피지 + 여유</li>
 * </ul>
 * 기준은 행마다 다를 수 있다 (변동성 방식). 모든 계산은 판단 시각까지의 과거 값만 쓴다.
 */
public record LabelSpec(String method, int horizon, String mode, double thresholdPct,
                        double volK, int volWindow, double feePct, double slippagePct, double marginPct) {

    public static final String FORWARD_RETURN = "FORWARD_RETURN";
    public static final String TRIPLE_BARRIER = "TRIPLE_BARRIER";
    public static final String FIXED = "FIXED";
    public static final String VOLATILITY = "VOLATILITY";
    public static final String COST = "COST";

    public static final List<Integer> HORIZONS = List.of(1, 2, 3, 5, 10, 15, 30, 60);
    public static final List<Double> FIXED_THRESHOLDS = List.of(0.05, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0, 1.5, 2.0, 3.0);
    public static final List<Double> VOL_KS = List.of(0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0);
    public static final List<Integer> VOL_WINDOWS = List.of(20, 60, 240, 1440);
    public static final List<Double> MARGINS = List.of(0.0, 0.05, 0.1, 0.2, 0.3, 0.5);

    public void validate() {
        if (!method.equals(FORWARD_RETURN) && !method.equals(TRIPLE_BARRIER)) {
            throw ApiException.badRequest("알 수 없는 정답 방식: " + method);
        }
        if (!HORIZONS.contains(horizon)) {
            throw ApiException.badRequest("예측 기간은 " + HORIZONS + " 봉 중에서 고르세요.");
        }
        switch (mode) {
            case FIXED -> {
                if (FIXED_THRESHOLDS.stream().noneMatch(t -> Math.abs(t - thresholdPct) < 1e-9)) {
                    throw ApiException.badRequest("고정 기준 %는 " + FIXED_THRESHOLDS + " 중에서 고르세요.");
                }
            }
            case VOLATILITY -> {
                if (VOL_KS.stream().noneMatch(k -> Math.abs(k - volK) < 1e-9) || !VOL_WINDOWS.contains(volWindow)) {
                    throw ApiException.badRequest("변동성 배수 · 계산 기간을 목록에서 고르세요.");
                }
            }
            case COST -> {
                if (feePct < 0 || feePct > 1 || slippagePct < 0 || slippagePct > 1
                        || MARGINS.stream().noneMatch(m -> Math.abs(m - marginPct) < 1e-9)) {
                    throw ApiException.badRequest("수수료 · 슬리피지는 0~1%, 여유는 목록에서 고르세요.");
                }
            }
            default -> throw ApiException.badRequest("알 수 없는 기준 방식: " + mode);
        }
    }

    /** 이 행의 기준(%). sigmaPct = 최근 1봉 수익률 표준편차(%) — 변동성 방식에서만 쓴다 */
    public double threshold(double sigmaPct) {
        return switch (mode) {
            case FIXED -> thresholdPct;
            case VOLATILITY -> volK * sigmaPct * Math.sqrt(horizon);
            case COST -> 2 * (feePct + slippagePct) + marginPct;
            default -> Double.NaN;
        };
    }

    public boolean needsVolatility() {
        return mode.equals(VOLATILITY);
    }

    /** 대표 기준 값 (DB 의 threshold_pct 칸 · 고정이 아니면 비용 방식은 계산값, 변동성은 0) */
    public double representativePct() {
        return switch (mode) {
            case FIXED -> thresholdPct;
            case COST -> threshold(0);
            default -> 0;
        };
    }

    /** 「다른 기준이었다면」 표에 쓸 같은 방식의 다른 값들 (첫 번째 축만 바꾼다) */
    public List<LabelSpec> alternatives() {
        List<LabelSpec> out = new ArrayList<>();
        switch (mode) {
            case FIXED -> FIXED_THRESHOLDS.forEach(t -> out.add(new LabelSpec(method, horizon, mode, t, volK, volWindow, feePct, slippagePct, marginPct)));
            case VOLATILITY -> VOL_KS.forEach(k -> out.add(new LabelSpec(method, horizon, mode, thresholdPct, k, volWindow, feePct, slippagePct, marginPct)));
            case COST -> MARGINS.forEach(m -> out.add(new LabelSpec(method, horizon, mode, thresholdPct, volK, volWindow, feePct, slippagePct, m)));
            default -> out.add(this);
        }
        return out;
    }

    /** 다른 기준 표의 행 이름 */
    public String alternativeLabel() {
        return switch (mode) {
            case FIXED -> "±" + fmt(thresholdPct) + "%";
            case VOLATILITY -> fmt(volK) + " × 변동성";
            case COST -> "비용 + 여유 " + fmt(marginPct) + "% (±" + fmt(threshold(0)) + "%)";
            default -> "";
        };
    }

    public boolean sameAlternative(LabelSpec other) {
        return alternativeLabel().equals(other.alternativeLabel());
    }

    /** 화면 설명 한 줄 */
    public String text(String intervalLabel) {
        String target = method.equals(TRIPLE_BARRIER)
                ? horizon + "봉(" + intervalLabel + ") 안에 목표가 · 손절가 중 먼저 닿는 쪽"
                : horizon + "봉(" + intervalLabel + ") 뒤 종가 수익률";
        String thr = switch (mode) {
            case FIXED -> "±" + fmt(thresholdPct) + "%";
            case VOLATILITY -> "±(최근 " + volWindow + "봉 변동성 × " + fmt(volK) + " × √" + horizon + ")";
            case COST -> "±" + fmt(threshold(0)) + "% (수수료 " + fmt(feePct) + "% · 슬리피지 " + fmt(slippagePct) + "% 왕복 + 여유 " + fmt(marginPct) + "%)";
            default -> "";
        };
        return target + " · 기준 " + thr;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", method);
        m.put("horizon", horizon);
        m.put("mode", mode);
        m.put("thresholdPct", thresholdPct);
        m.put("volK", volK);
        m.put("volWindow", volWindow);
        m.put("feePct", feePct);
        m.put("slippagePct", slippagePct);
        m.put("marginPct", marginPct);
        return m;
    }

    public static LabelSpec fromMap(Map<String, Object> m, double fallbackThreshold) {
        if (m == null) {
            return new LabelSpec(FORWARD_RETURN, 1, FIXED, fallbackThreshold, 1, 60, 0.05, 0, 0);
        }
        return new LabelSpec(String.valueOf(m.get("method")), num(m.get("horizon")).intValue(), String.valueOf(m.get("mode")),
                num(m.get("thresholdPct")).doubleValue(), num(m.get("volK")).doubleValue(), num(m.get("volWindow")).intValue(),
                num(m.get("feePct")).doubleValue(), num(m.get("slippagePct")).doubleValue(), num(m.get("marginPct")).doubleValue());
    }

    private static Number num(Object o) {
        return o instanceof Number n ? n : Double.valueOf(String.valueOf(o));
    }

    static String fmt(double v) {
        String s = String.format("%.3f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
