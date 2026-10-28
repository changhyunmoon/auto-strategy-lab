package com.mch.strategy.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mch.strategy.indicator.IndicatorConfig.Atr;
import com.mch.strategy.indicator.IndicatorConfig.Bb;
import com.mch.strategy.indicator.IndicatorConfig.Ma;
import com.mch.strategy.indicator.IndicatorConfig.Macd;
import com.mch.strategy.indicator.IndicatorConfig.Rsi;
import com.mch.strategy.indicator.IndicatorConfig.Volume;

/** 노션 문서의 조합 수 예시와 이름 규칙이 그대로 나오는지 확인한다. */
class IndicatorPlannerTest {

    static final IndicatorConfig D = IndicatorConfig.defaults();

    static IndicatorConfig only(Volume v, Ma m, Rsi r, Macd mc, Atr a, Bb b) {
        return new IndicatorConfig(
                v != null ? v : off(D.volume()), m != null ? m : off(D.ma()), r != null ? r : off(D.rsi()),
                mc != null ? mc : off(D.macd()), a != null ? a : off(D.atr()), b != null ? b : off(D.bb()));
    }

    static Volume off(Volume v) { return new Volume(false, v.periods(), v.sma(), v.rvol(), v.vo(), v.mfi(), v.cmf(), v.obv()); }
    static Ma off(Ma v) { return new Ma(false, v.types(), v.periods(), v.slopeLags(), v.pairScope(), v.raw(), v.distance(), v.slope(), v.gap(), v.above()); }
    static Rsi off(Rsi v) { return new Rsi(false, v.periods(), v.deltaLags(), v.bands(), v.stochRanges(), v.raw(), v.center(), v.delta(), v.pairGap(), v.pairAbove(), v.zones(), v.stoch()); }
    static Macd off(Macd v) { return new Macd(false, v.fast(), v.slow(), v.signal(), v.deltaLags(), v.base(), v.pct(), v.delta(), v.position(), v.cross()); }
    static Atr off(Atr v) { return new Atr(false, v.periods(), v.deltaLags(), v.tr(), v.atr(), v.atrPct(), v.trToAtr(), v.delta(), v.pairRatio(), v.pairAbove()); }
    static Bb off(Bb v) { return new Bb(false, v.periods(), v.multipliers(), v.mid(), v.std(), v.z(), v.bands(), v.width(), v.percentB(), v.flags()); }

    static List<String> names(IndicatorPlan p) {
        return p.columns().stream().map(ColumnSpec::name).toList();
    }

    @Test
    void volume_23_columns() {
        IndicatorPlan p = IndicatorPlanner.plan(only(D.volume(), null, null, null, null, null));
        assertThat(p.plannedCount()).isEqualTo(23);
        assertThat(p.columns()).hasSize(23);
        assertThat(names(p)).contains("volume_sma_5", "rvol_60", "vo_5_10", "vo_20_60", "mfi_20", "cmf_5", "obv");
        assertThat(p.ok()).isTrue();
    }

    @Test
    void ma_112_same_type_and_304_all_pairs() {
        IndicatorPlan same = IndicatorPlanner.plan(only(null, D.ma(), null, null, null, null));
        assertThat(same.columns()).hasSize(112);
        assertThat(names(same)).contains("sma_20", "distance_ema_20", "slope_ema_20_5", "gap_sma_5__sma_20", "above_sma_5__sma_20");

        Ma all = new Ma(true, D.ma().types(), D.ma().periods(), D.ma().slopeLags(), Ma.ALL, true, true, true, true, true);
        IndicatorPlan p = IndicatorPlanner.plan(only(null, all, null, null, null, null));
        assertThat(p.columns()).hasSize(304);
        assertThat(names(p)).contains("gap_sma_5__ema_20", "gap_sma_20__ema_20");
        assertThat(names(p)).doesNotContain("gap_ema_20__sma_20");
    }

    @Test
    void rsi_36_and_44_with_stoch() {
        IndicatorPlan p = IndicatorPlanner.plan(only(null, null, D.rsi(), null, null, null));
        assertThat(p.columns()).hasSize(36);
        assertThat(names(p)).contains("rsi_20", "rsi_center_20", "rsi_delta_20_5", "rsi_gap_5_20", "rsi_pair_above_5_20",
                "rsi_lower_20_30", "rsi_upper_20_70");
        Rsi r = D.rsi();
        Rsi withStoch = new Rsi(true, r.periods(), r.deltaLags(), r.bands(), r.stochRanges(), true, true, true, true, true, true, true);
        IndicatorPlan s = IndicatorPlanner.plan(only(null, null, withStoch, null, null, null));
        assertThat(s.columns()).hasSize(44);
        assertThat(names(s)).contains("stochrsi_10_20");
    }

    @Test
    void macd_216_planned_and_duplicates_removed() {
        IndicatorPlan p = IndicatorPlanner.plan(only(null, null, null, D.macd(), null, null));
        assertThat(p.plannedCount()).isEqualTo(216);
        // MACD 선 계열(macd · macd_pct · macd_delta×2 · macd_pos · cross 2개 = 7)은 신호 기간과 무관 → 6쌍 × 7 만큼 중복
        assertThat(p.columns()).hasSize(216 - 6 * 7);
        assertThat(names(p)).contains("macd_12_26_5", "signal_12_26_9", "hist_12_26_9", "macd_pct_12_26_5",
                "hist_delta_12_26_9_5", "hist_zero_cross_up_12_26_9");
        assertThat(p.duplicates()).anyMatch(d -> d.name().equals("macd_12_26_9") && d.sameAs().equals("macd_12_26_5"));
    }

    @Test
    void macd_rejects_when_no_fast_less_than_slow() {
        Macd m = new Macd(true, List.of(30), List.of(20), List.of(9), List.of(1), true, false, false, false, false);
        IndicatorPlan p = IndicatorPlanner.plan(only(null, null, null, m, null, null));
        assertThat(p.ok()).isFalse();
        assertThat(p.messages()).anyMatch(x -> x.level().equals("ERROR") && x.text().contains("빠른 기간 < 느린 기간"));
    }

    @Test
    void atr_33() {
        IndicatorPlan p = IndicatorPlanner.plan(only(null, null, null, null, D.atr(), null));
        assertThat(p.columns()).hasSize(33);
        assertThat(names(p)).contains("tr", "atr_20", "atr_pct_20", "tr_to_atr_20", "atr_delta_20_5", "atr_ratio_5_20", "atr_above_5_20");
    }

    @Test
    void bb_45_alone_and_42_with_sma_dedup() {
        IndicatorPlan alone = IndicatorPlanner.plan(only(null, null, null, null, null, D.bb()));
        assertThat(alone.columns()).hasSize(45);
        assertThat(names(alone)).contains("bb_mid_20", "bb_std_20", "bb_z_20", "bb_upper_20_1p5", "bb_lower_20_2",
                "bb_width_20_2", "bb_percent_b_20_1p5", "bb_above_upper_20_2", "bb_below_lower_20_2");

        IndicatorPlan withMa = IndicatorPlanner.plan(only(null, D.ma(), null, null, null, D.bb()));
        assertThat(withMa.countFor(IndicatorKind.BB)).isEqualTo(42);
        assertThat(withMa.duplicates()).anyMatch(d -> d.name().equals("bb_mid_20") && d.sameAs().equals("sma_20"));
    }

    @Test
    void invalid_inputs_are_reported() {
        Volume v = new Volume(true, List.of(0, 5), true, false, false, false, false, false);
        assertThat(IndicatorPlanner.plan(only(v, null, null, null, null, null)).ok()).isFalse();

        Rsi bad = new Rsi(true, List.of(14), List.of(), List.of(new IndicatorConfig.Band(70, 30)), List.of(), false, false, false, false, false, true, false);
        assertThat(IndicatorPlanner.plan(only(null, null, bad, null, null, null)).messages())
                .anyMatch(m -> m.level().equals("ERROR") && m.text().contains("하단 < 상단"));

        Volume single = new Volume(true, List.of(20), false, false, true, false, false, true);
        IndicatorPlan p = IndicatorPlanner.plan(only(single, null, null, null, null, null));
        assertThat(p.columns()).extracting(ColumnSpec::name).containsExactly("obv");
        assertThat(p.messages()).anyMatch(m -> m.level().equals("WARN") && m.text().contains("VO"));
    }

    @Test
    void every_default_column_computes_with_right_length_and_warmup() {
        IndicatorPlan p = IndicatorPlanner.plan(D);
        int n = 400;
        double[] o = new double[n], h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            double base = 100 + 10 * Math.sin(i / 7.0) + i * 0.05;
            o[i] = base;
            c[i] = base + Math.cos(i / 3.0);
            h[i] = Math.max(o[i], c[i]) + 0.5;
            l[i] = Math.min(o[i], c[i]) - 0.5;
            v[i] = 10 + (i % 13);
        }
        Ctx ctx = new Ctx(o, h, l, c, v);
        for (ColumnSpec col : p.columns()) {
            double[] values = col.calc().apply(ctx);
            assertThat(values).as(col.name()).hasSize(n);
            int first = 0;
            while (first < n && Double.isNaN(values[first])) first++;
            assertThat(first).as(col.name() + " 첫 유효 위치 = warmup").isEqualTo(col.warmup());
        }
    }
}
