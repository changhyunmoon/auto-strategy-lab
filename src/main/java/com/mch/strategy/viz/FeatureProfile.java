package com.mch.strategy.viz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 변수 하나를 여러 각도로 요약한다 (변수 탐색 화면용). DB 와 상관없는 순수 계산.
 * <ul>
 *   <li>분포: 기초 통계 · 히스토그램</li>
 *   <li>N봉 뒤 수익률과의 관계: 값 구간(10분위)별 평균 수익률 · 상승 비율, 순위 상관(IC)</li>
 *   <li>지속성: 자기상관 (지금 값이 몇 봉 뒤까지 비슷하게 유지되는지)</li>
 *   <li>시간대(KST)별 평균</li>
 *   <li>다른 변수와의 상관 상위</li>
 * </ul>
 */
final class FeatureProfile {

    static final List<Integer> HORIZONS = List.of(1, 3, 6, 12, 24);
    static final int DEFAULT_HORIZON = 6;
    static final int BUCKETS = 10;
    /** 서로 다른 값이 이 개수 이하면 값 하나하나를 구간으로 본다 (방향 ±1 · 요일 등) */
    static final int DISCRETE_MAX = 12;
    static final int HIST_BINS = 40;
    static final int ACF_LAGS = 30;
    static final int RELATED = 8;

    private FeatureProfile() {
    }

    /**
     * @param time   봉 시작 시각 (epoch ms, 오름차순)
     * @param x      살펴볼 변수
     * @param close  종가 (N봉 뒤 수익률 계산용)
     * @param others 상관을 비교할 다른 변수들 (열 이름 → 값)
     */
    static Map<String, Object> compute(long[] time, double[] x, double[] close, int intervalMinutes, int horizon,
                                       Map<String, double[]> others) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> stats = stats(x);
        boolean discrete = (boolean) stats.get("discrete");
        out.put("stats", stats);

        double[] fwd = forwardReturnPct(time, close, intervalMinutes, horizon);
        out.put("horizon", horizon);
        out.put("forward", buckets(x, fwd, discrete));

        List<Map<String, Object>> ic = new ArrayList<>();
        for (int h : HORIZONS) {
            double[] f = h == horizon ? fwd : forwardReturnPct(time, close, intervalMinutes, h);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("horizon", h);
            m.put("ic", spearman(x, f));
            ic.add(m);
        }
        out.put("icByHorizon", ic);
        out.put("acf", acf(x, ACF_LAGS));
        out.put("hourly", hourly(time, x));
        out.put("related", related(x, others));
        return out;
    }

    // ---------- 분포 ----------

    static Map<String, Object> stats(double[] all) {
        double[] x = Arrays.stream(all).filter(Double::isFinite).sorted().toArray();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.length);
        out.put("count", x.length);
        out.put("missingRate", all.length == 0 ? 0.0 : 1 - x.length / (double) all.length);
        List<Double> distinct = distinctValues(x, DISCRETE_MAX + 1);
        boolean discrete = x.length > 0 && distinct.size() <= DISCRETE_MAX;
        out.put("discrete", discrete);
        if (x.length == 0) {
            out.put("hist", Map.of("centers", List.of(), "counts", List.of()));
            return out;
        }
        double mean = Arrays.stream(x).average().orElse(0);
        double sd = Math.sqrt(Arrays.stream(x).map(d -> (d - mean) * (d - mean)).sum() / x.length);
        out.put("mean", mean);
        out.put("std", sd);
        out.put("min", x[0]);
        out.put("q05", quantile(x, 0.05));
        out.put("q25", quantile(x, 0.25));
        out.put("median", quantile(x, 0.5));
        out.put("q75", quantile(x, 0.75));
        out.put("q95", quantile(x, 0.95));
        out.put("max", x[x.length - 1]);
        out.put("zeroRate", Arrays.stream(x).filter(d -> d == 0).count() / (double) x.length);
        out.put("hist", discrete ? discreteHist(x, distinct) : hist(x));
        return out;
    }

    /** 양 끝 0.5% 는 끝 막대에 합친다 (튀는 값 몇 개 때문에 막대가 한쪽에 몰리지 않게). */
    private static Map<String, Object> hist(double[] sorted) {
        double lo = quantile(sorted, 0.005), hi = quantile(sorted, 0.995);
        if (hi <= lo) {
            lo = sorted[0];
            hi = sorted[sorted.length - 1];
        }
        double width = (hi - lo) / HIST_BINS;
        int[] counts = new int[HIST_BINS];
        int clipped = 0;
        for (double d : sorted) {
            if (d < lo || d > hi) clipped++;
            int b = width == 0 ? 0 : (int) Math.floor((d - lo) / width);
            counts[Math.max(0, Math.min(HIST_BINS - 1, b))]++;
        }
        List<Double> centers = new ArrayList<>();
        for (int b = 0; b < HIST_BINS; b++) centers.add(lo + width * (b + 0.5));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("centers", centers);
        out.put("counts", Arrays.stream(counts).boxed().toList());
        out.put("clipped", clipped);
        return out;
    }

    private static Map<String, Object> discreteHist(double[] sorted, List<Double> distinct) {
        List<Integer> counts = new ArrayList<>();
        int j = 0;
        for (double v : distinct) {
            int c = 0;
            while (j < sorted.length && sorted[j] == v) {
                c++;
                j++;
            }
            counts.add(c);
        }
        return Map.of("centers", distinct, "counts", counts, "clipped", 0);
    }

    /** 정렬된 값에서 서로 다른 값을 limit 개까지 */
    private static List<Double> distinctValues(double[] sorted, int limit) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < sorted.length && out.size() < limit; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) out.add(sorted[i]);
        }
        return out;
    }

    // ---------- N봉 뒤 수익률 ----------

    /** (N봉 뒤 종가 ÷ 지금 종가 − 1) × 100. 사이에 빈 구간이 있으면 NaN. */
    static double[] forwardReturnPct(long[] time, double[] close, int intervalMinutes, int horizon) {
        double[] out = new double[close.length];
        Arrays.fill(out, Double.NaN);
        long span = horizon * intervalMinutes * 60_000L;
        for (int i = 0; i + horizon < close.length; i++) {
            if (time[i + horizon] - time[i] != span) continue;
            if (close[i] > 0 && Double.isFinite(close[i + horizon])) {
                out[i] = (close[i + horizon] / close[i] - 1) * 100;
            }
        }
        return out;
    }

    /**
     * 변수 값 구간별 N봉 뒤 수익률. 연속 값은 10분위(같은 값이 많으면 구간이 합쳐져 줄어든다),
     * 값 종류가 적으면 값마다 한 구간.
     */
    static Map<String, Object> buckets(double[] x, double[] fwd, boolean discrete) {
        int n = 0;
        for (int i = 0; i < x.length; i++) if (Double.isFinite(x[i]) && Double.isFinite(fwd[i])) n++;
        double[] xs = new double[n];
        double[] fs = new double[n];
        for (int i = 0, k = 0; i < x.length; i++) {
            if (Double.isFinite(x[i]) && Double.isFinite(fwd[i])) {
                xs[k] = x[i];
                fs[k++] = fwd[i];
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n);
        if (n == 0) {
            out.put("buckets", List.of());
            return out;
        }

        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        // 구간의 아래 경계들. 값 v 는 「v 이하인 경계 수」번째 구간에 들어간다.
        List<Double> edges = new ArrayList<>();
        if (discrete) {
            List<Double> distinct = distinctValues(sorted, DISCRETE_MAX + 1);
            edges.addAll(distinct.subList(1, distinct.size()));
        } else {
            for (int k = 1; k < BUCKETS; k++) {
                double e = sorted[(int) ((long) k * n / BUCKETS)];
                if (e > sorted[0] && (edges.isEmpty() || e > edges.get(edges.size() - 1))) edges.add(e);
            }
        }
        double[] edgeArr = edges.stream().mapToDouble(Double::doubleValue).toArray();
        int m = edgeArr.length + 1;
        double[] lo = new double[m], hi = new double[m], sum = new double[m];
        int[] cnt = new int[m], up = new int[m];
        Arrays.fill(lo, Double.POSITIVE_INFINITY);
        Arrays.fill(hi, Double.NEGATIVE_INFINITY);
        double total = 0;
        int totalUp = 0;
        for (int i = 0; i < n; i++) {
            int b = upperBound(edgeArr, xs[i]);
            lo[b] = Math.min(lo[b], xs[i]);
            hi[b] = Math.max(hi[b], xs[i]);
            sum[b] += fs[i];
            cnt[b]++;
            if (fs[i] > 0) up[b]++;
            total += fs[i];
            if (fs[i] > 0) totalUp++;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int b = 0; b < m; b++) {
            if (cnt[b] == 0) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("lo", lo[b]);
            r.put("hi", hi[b]);
            r.put("count", cnt[b]);
            r.put("meanPct", sum[b] / cnt[b]);
            r.put("upRate", up[b] / (double) cnt[b]);
            list.add(r);
        }
        out.put("buckets", list);
        out.put("meanPct", total / n);
        out.put("upRate", totalUp / (double) n);
        return out;
    }

    /** 정렬된 a 에서 v 이하인 값의 개수 */
    private static int upperBound(double[] a, double v) {
        int l = 0, r = a.length;
        while (l < r) {
            int mid = (l + r) >>> 1;
            if (a[mid] <= v) l = mid + 1;
            else r = mid;
        }
        return l;
    }

    /** 순위 상관 (스피어만). 같은 값은 평균 순위. 둘 다 값이 있는 행만. */
    static double spearman(double[] a, double[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) if (Double.isFinite(a[i]) && Double.isFinite(b[i])) n++;
        if (n < 3) return Double.NaN;
        double[] as = new double[n], bs = new double[n];
        for (int i = 0, k = 0; i < a.length; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])) {
                as[k] = a[i];
                bs[k++] = b[i];
            }
        }
        return VizService.pearson(ranks(as), ranks(bs));
    }

    private static double[] ranks(double[] v) {
        Integer[] idx = new Integer[v.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingDouble(i -> v[i]));
        double[] r = new double[v.length];
        int i = 0;
        while (i < idx.length) {
            int j = i;
            while (j + 1 < idx.length && v[idx[j + 1]] == v[idx[i]]) j++;
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    // ---------- 지속성 · 시간대 · 관련 변수 ----------

    /** 자기상관: lag 봉 떨어진 자기 자신과의 상관 (1 ~ maxLag) */
    static List<Double> acf(double[] x, int maxLag) {
        List<Double> out = new ArrayList<>();
        for (int lag = 1; lag <= maxLag && lag < x.length; lag++) {
            double[] a = Arrays.copyOfRange(x, 0, x.length - lag);
            double[] b = Arrays.copyOfRange(x, lag, x.length);
            out.add(VizService.pearson(nanIfInfinite(a), nanIfInfinite(b)));
        }
        return out;
    }

    /** KST 시각(0~23시)별 평균 */
    static Map<String, Object> hourly(long[] time, double[] x) {
        double[] sum = new double[24];
        int[] cnt = new int[24];
        for (int i = 0; i < x.length; i++) {
            if (!Double.isFinite(x[i])) continue;
            int h = (int) Math.floorMod(time[i] / 3_600_000L + 9, 24L);
            sum[h] += x[i];
            cnt[h]++;
        }
        List<Double> mean = new ArrayList<>();
        for (int h = 0; h < 24; h++) mean.add(cnt[h] == 0 ? Double.NaN : sum[h] / cnt[h]);
        return Map.of("mean", mean, "count", Arrays.stream(cnt).boxed().toList());
    }

    /** 다른 변수와의 피어슨 상관 중 절댓값이 큰 순 */
    static List<Map<String, Object>> related(double[] x, Map<String, double[]> others) {
        double[] clean = nanIfInfinite(x);
        List<Map<String, Object>> out = new ArrayList<>();
        others.forEach((name, values) -> {
            double r = VizService.pearson(clean, nanIfInfinite(values));
            if (Double.isNaN(r)) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("column", name);
            m.put("r", r);
            out.add(m);
        });
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> -Math.abs((double) m.get("r"))));
        return out.size() > RELATED ? new ArrayList<>(out.subList(0, RELATED)) : out;
    }

    private static double[] nanIfInfinite(double[] x) {
        double[] out = x.clone();
        for (int i = 0; i < out.length; i++) if (Double.isInfinite(out[i])) out[i] = Double.NaN;
        return out;
    }

    private static double quantile(double[] sorted, double q) {
        double pos = (sorted.length - 1) * q;
        int base = (int) Math.floor(pos);
        double next = base + 1 < sorted.length ? sorted[base + 1] : sorted[base];
        return sorted[base] + (next - sorted[base]) * (pos - base);
    }
}
