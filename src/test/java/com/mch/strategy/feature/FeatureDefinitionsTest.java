package com.mch.strategy.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.mch.strategy.feature.definitions.RangeFeatures;
import com.mch.strategy.feature.definitions.RelationFeatures;
import com.mch.strategy.feature.definitions.ReturnFeatures;
import com.mch.strategy.feature.definitions.TimeFeatures;
import com.mch.strategy.feature.definitions.TrendFeatures;
import com.mch.strategy.feature.definitions.VolatilityFeatures;
import com.mch.strategy.feature.definitions.VolumeFeatures;

/** 등록된 지표 전체를 가짜 봉 데이터로 계산해 본다. */
class FeatureDefinitionsTest {

    static AnnotationConfigApplicationContext ctx;
    static FeatureRegistry registry;

    @BeforeAll
    static void setUp() {
        ctx = new AnnotationConfigApplicationContext(ReturnFeatures.class, TrendFeatures.class, RangeFeatures.class,
                VolatilityFeatures.class, VolumeFeatures.class, RelationFeatures.class, TimeFeatures.class,
                FeatureRegistry.class);
        registry = ctx.getBean(FeatureRegistry.class);
    }

    @AfterAll
    static void tearDown() {
        ctx.close();
    }

    @Test
    void registry_has_all_planned_features() {
        assertThat(registry.all()).hasSize(28);
        int columns = registry.all().stream().mapToInt(f -> f.outputColumns().size()).sum();
        assertThat(columns).isEqualTo(45);
        Set<String> names = new HashSet<>();
        registry.all().forEach(f -> f.outputColumns().forEach(c -> assertThat(names.add(c)).as("열 이름 중복: " + c).isTrue()));
        assertThat(registry.byGroup().keySet()).containsExactly(FeatureGroup.values());
    }

    @Test
    void every_feature_computes_full_length_columns() {
        FeatureInput input = fakeInput(2000, 15);
        for (FeatureDefinition f : registry.all()) {
            Map<String, double[]> out = f.compute(input);
            assertThat(out.keySet()).as(f.code()).containsExactlyElementsOf(f.outputColumns());
            out.forEach((col, values) -> {
                assertThat(values).as(col).hasSize(input.size());
                // 워밍업 이후 마지막 값은 계산되어 있어야 한다
                assertThat(values[values.length - 1]).as(col + " 마지막 값").isNotNaN();
            });
        }
    }

    @Test
    void bounded_features_stay_in_range() {
        FeatureInput input = fakeInput(2000, 1);
        assertRange(registry.find("rsi").orElseThrow().compute(input).get("rsi_14"), 0, 1);
        assertRange(registry.find("donchian_pos").orElseThrow().compute(input).get("donchian_pos_20"), 0, 1);
        assertRange(registry.find("taker_buy_ratio").orElseThrow().compute(input).get("taker_buy_ratio"), 0, 1);
        assertRange(registry.find("asset_corr").orElseThrow().compute(input).get("asset_corr_60"), -1, 1);
        assertRange(registry.find("ema_alignment").orElseThrow().compute(input).get("ema_alignment"), -1, 1);
    }

    @Test
    void features_do_not_look_ahead() {
        // 앞부분만 잘라 계산한 값과 전체로 계산한 값의 앞부분이 같아야 한다 (미래 봉을 쓰지 않음)
        FeatureInput full = fakeInput(1500, 5);
        FeatureInput head = cut(full, 1000);
        for (FeatureDefinition f : registry.all()) {
            Map<String, double[]> a = f.compute(full), b = f.compute(head);
            for (String col : f.outputColumns()) {
                double[] fa = Arrays.copyOf(a.get(col), 1000);
                assertThat(fa).as(col).containsExactly(b.get(col));
            }
        }
    }

    @Test
    void warmup_grows_with_interval() {
        FeatureDefinition alignment = registry.find("ema_alignment").orElseThrow();
        assertThat(alignment.warmupMinutes(15)).isEqualTo(240 * 15);
        FeatureDefinition turnover = registry.find("turnover_z").orElseThrow();
        assertThat(turnover.warmupMinutes(1)).isEqualTo(1440);
    }

    static void assertRange(double[] values, double min, double max) {
        for (double v : values) {
            if (!Double.isNaN(v)) {
                assertThat(v).isBetween(min, max);
            }
        }
    }

    static FeatureInput fakeInput(int n, int minutes) {
        Random r = new Random(42);
        long[] t = new long[n];
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n], qv = new double[n];
        double[] refC = new double[n], refV = new double[n], taker = new double[n], trades = new double[n], other = new double[n];
        double p = 100_000_000, q = 5_000_000;
        for (int i = 0; i < n; i++) {
            t[i] = 1_760_000_000_000L + i * minutes * 60_000L;
            o[i] = p;
            p *= 1 + (r.nextGaussian() * 0.002);
            c[i] = p;
            h[i] = Math.max(o[i], c[i]) * (1 + r.nextDouble() * 0.001);
            l[i] = Math.min(o[i], c[i]) * (1 - r.nextDouble() * 0.001);
            v[i] = 1 + r.nextDouble() * 5;
            qv[i] = v[i] * c[i];
            refC[i] = c[i] / 1400 * (1 + r.nextGaussian() * 0.0005);
            refV[i] = v[i] * (5 + r.nextDouble() * 10);
            taker[i] = refV[i] * (0.3 + r.nextDouble() * 0.4);
            trades[i] = 100 + r.nextInt(500);
            q *= 1 + (r.nextGaussian() * 0.003);
            other[i] = q;
        }
        return new FeatureInput(t, o, h, l, c, v, qv, refC, refV, taker, trades, other, minutes);
    }

    static FeatureInput cut(FeatureInput in, int n) {
        return new FeatureInput(Arrays.copyOf(in.time(), n), Arrays.copyOf(in.open(), n), Arrays.copyOf(in.high(), n),
                Arrays.copyOf(in.low(), n), Arrays.copyOf(in.close(), n), Arrays.copyOf(in.volume(), n),
                Arrays.copyOf(in.quoteVolume(), n), Arrays.copyOf(in.refClose(), n), Arrays.copyOf(in.refVolume(), n),
                Arrays.copyOf(in.refTakerBuy(), n), Arrays.copyOf(in.refTradeCount(), n), Arrays.copyOf(in.otherClose(), n),
                in.intervalMinutes());
    }

    @SuppressWarnings("unused")
    private static List<String> names() {
        return registry.all().stream().map(FeatureDefinition::code).toList();
    }
}
