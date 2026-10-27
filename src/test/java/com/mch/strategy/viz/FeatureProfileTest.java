package com.mch.strategy.viz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class FeatureProfileTest {

    private static final long MIN = 60_000L;

    @Test
    void forward_return_skips_gaps() {
        long[] t = {0, MIN, 2 * MIN, 5 * MIN, 6 * MIN}; // 2분 → 5분 사이 빈 구간
        double[] close = {100, 101, 102, 103, 104};
        double[] f = FeatureProfile.forwardReturnPct(t, close, 1, 1);
        assertThat(f[0]).isCloseTo(1.0, within(1e-9));
        assertThat(f[1]).isCloseTo((102 / 101.0 - 1) * 100, within(1e-9));
        assertThat(f[2]).isNaN();
        assertThat(f[3]).isCloseTo((104 / 103.0 - 1) * 100, within(1e-9));
        assertThat(f[4]).isNaN();
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuous_values_split_into_ten_buckets_in_order() {
        int n = 1000;
        double[] x = new double[n], fwd = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = i;
            fwd[i] = i < 500 ? -1 : 1;
        }
        Map<String, Object> r = FeatureProfile.buckets(x, fwd, false);
        List<Map<String, Object>> b = (List<Map<String, Object>>) r.get("buckets");
        assertThat(b).hasSize(10);
        assertThat(b.get(0).get("count")).isEqualTo(100);
        assertThat((double) b.get(0).get("lo")).isEqualTo(0);
        assertThat((double) b.get(9).get("hi")).isEqualTo(999);
        assertThat((double) b.get(0).get("upRate")).isEqualTo(0);
        assertThat((double) b.get(9).get("meanPct")).isEqualTo(1);
        assertThat((double) r.get("upRate")).isEqualTo(0.5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void discrete_values_get_one_bucket_each() {
        double[] x = {-1, 1, 1, -1, 1, Double.NaN};
        double[] fwd = {-0.2, 0.4, 0.2, -0.1, Double.NaN, 1};
        Map<String, Object> stats = FeatureProfile.stats(x);
        assertThat(stats.get("discrete")).isEqualTo(true);
        Map<String, Object> r = FeatureProfile.buckets(x, fwd, true);
        List<Map<String, Object>> b = (List<Map<String, Object>>) r.get("buckets");
        assertThat(b).hasSize(2);
        assertThat((double) b.get(0).get("lo")).isEqualTo(-1);
        assertThat(b.get(0).get("count")).isEqualTo(2);
        assertThat((double) b.get(1).get("meanPct")).isCloseTo(0.3, within(1e-9));
    }

    @Test
    void heavily_tied_values_merge_buckets_instead_of_overlapping() {
        double[] x = new double[100], fwd = new double[100];
        for (int i = 0; i < 100; i++) {
            x[i] = i < 70 ? 0 : i; // 70% 가 0
            fwd[i] = 0.1;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> b = (List<Map<String, Object>>) FeatureProfile.buckets(x, fwd, false).get("buckets");
        assertThat(b.get(0).get("count")).isEqualTo(70);
        for (int i = 1; i < b.size(); i++) {
            assertThat((double) b.get(i).get("lo")).isGreaterThan((double) b.get(i - 1).get("hi"));
        }
    }

    @Test
    void spearman_is_rank_based() {
        double[] a = {1, 2, 3, 4, 5, Double.NaN};
        double[] b = {1, 4, 9, 16, 1000, 3};
        assertThat(FeatureProfile.spearman(a, b)).isCloseTo(1, within(1e-12));
        double[] c = {5, 4, 3, 2, 1, 0};
        assertThat(FeatureProfile.spearman(a, c)).isCloseTo(-1, within(1e-12));
    }

    @Test
    void stats_and_profile_shape() {
        Random rnd = new Random(1);
        int n = 3000;
        long[] t = new long[n];
        double[] close = new double[n], x = new double[n], other = new double[n];
        double p = 100;
        for (int i = 0; i < n; i++) {
            t[i] = i * 5 * MIN;
            p *= 1 + rnd.nextGaussian() * 0.001;
            close[i] = p;
            x[i] = i < 10 ? Double.NaN : rnd.nextGaussian();
            other[i] = x[i] * 2 + 1;
        }
        Map<String, Object> out = FeatureProfile.compute(t, x, close, 5, 6, Map.of("twin", other));
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) out.get("stats");
        assertThat(stats.get("count")).isEqualTo(n - 10);
        assertThat(stats.get("discrete")).isEqualTo(false);
        assertThat((double) stats.get("missingRate")).isCloseTo(10.0 / n, within(1e-9));
        assertThat((List<?>) out.get("acf")).hasSize(FeatureProfile.ACF_LAGS);
        assertThat((List<?>) out.get("icByHorizon")).hasSize(FeatureProfile.HORIZONS.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> related = (List<Map<String, Object>>) out.get("related");
        assertThat((double) related.get(0).get("r")).isCloseTo(1, within(1e-9));
        @SuppressWarnings("unchecked")
        Map<String, Object> hourly = (Map<String, Object>) out.get("hourly");
        assertThat((List<?>) hourly.get("mean")).hasSize(24);
    }
}
