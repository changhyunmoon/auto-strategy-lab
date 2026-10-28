package com.mch.strategy.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.mch.strategy.split.SplitCalculator;
import com.mch.strategy.split.SplitConfig;

class LabelCalculatorTest {

    static final long M = 60_000L;

    static long[] minutes(int... idx) {
        long[] t = new long[idx.length];
        for (int i = 0; i < idx.length; i++) t[i] = idx[i] * M;
        return t;
    }

    static LabelConfig cfg(int h, double p, double l, String sameBar) {
        return new LabelConfig(h, p, l, "LONG", "NEXT_OPEN", sameBar, false, 0, 0);
    }

    @Test
    void target_stop_timeout_and_end_time() {
        long[] t = minutes(0, 1, 2, 3, 4);
        double[] o = {100, 100, 100, 100, 100};
        double[] h = {100, 100.5, 101.2, 100, 100};
        double[] l = {100, 99.8, 100, 100, 100};
        double[] c = {100, 100, 101, 100, 100.3};
        LabelCalculator.Result r = LabelCalculator.compute(t, o, h, l, c, cfg(2, 1, 1, "AMBIGUOUS"));
        // 행 0: 진입 = 봉1 시가 100, 목표 101 → 봉2 고가 101.2 에서 도달
        assertThat(LabelOutcome.of(r.status[0])).isEqualTo(LabelOutcome.TARGET);
        assertThat(r.endTime[0]).isEqualTo(3 * M);
        assertThat(r.bars[0]).isEqualTo(2);
        // 행 2: 관찰 봉 3,4 → 사건 없음 → 시간 만료, 수익률 = 100.3/100 − 1
        assertThat(LabelOutcome.of(r.status[2])).isEqualTo(LabelOutcome.TIMEOUT);
        assertThat(r.endTime[2]).isEqualTo(5 * M);
        assertThat(r.timeoutReturn[2]).isCloseTo(0.3, within(1e-9));
        // 행 3: 관찰 봉 4, 5(없음) → 데이터 부족
        assertThat(LabelOutcome.of(r.status[3])).isEqualTo(LabelOutcome.NO_FUTURE);
        assertThat(LabelOutcome.of(r.status[4])).isEqualTo(LabelOutcome.NO_FUTURE);
    }

    @Test
    void same_bar_rule_and_gap() {
        long[] t = minutes(0, 1, 3);
        double[] o = {100, 100, 100};
        double[] h = {100, 102, 100};
        double[] l = {100, 98, 100};
        double[] c = {100, 100, 100};
        assertThat(LabelOutcome.of(LabelCalculator.compute(t, o, h, l, c, cfg(3, 1, 1, "AMBIGUOUS")).status[0]))
                .isEqualTo(LabelOutcome.AMBIGUOUS);
        assertThat(LabelOutcome.of(LabelCalculator.compute(t, o, h, l, c, cfg(3, 1, 1, "STOP_FIRST")).status[0]))
                .isEqualTo(LabelOutcome.STOP);
        // 행 1: 다음 봉이 1분 뒤가 아님 → 진입 불가 = 빈 1분
        assertThat(LabelOutcome.of(LabelCalculator.compute(t, o, h, l, c, cfg(3, 1, 1, "AMBIGUOUS")).status[1]))
                .isEqualTo(LabelOutcome.GAP);
    }

    @Test
    void cost_widens_target_and_short_direction() {
        LabelConfig withCost = new LabelConfig(5, 1, 1, "LONG", "CLOSE", "AMBIGUOUS", true, 0.05, 0.02);
        assertThat(withCost.grossTargetPct()).isCloseTo(1.14, within(1e-9));
        assertThat(withCost.grossStopPct()).isCloseTo(0.86, within(1e-9));
        assertThat(new LabelConfig(5, 1, 0.1, "LONG", "CLOSE", "AMBIGUOUS", true, 0.05, 0.02).problems()).isNotEmpty();

        long[] t = minutes(0, 1);
        double[] o = {100, 100}, h = {100, 100.2}, l = {100, 98.9}, c = {100, 99};
        LabelConfig shortCfg = new LabelConfig(1, 1, 1, "SHORT", "CLOSE", "AMBIGUOUS", false, 0, 0);
        assertThat(LabelOutcome.of(LabelCalculator.compute(t, o, h, l, c, shortCfg).status[0])).isEqualTo(LabelOutcome.TARGET);
    }

    @Test
    void split_assigns_and_excludes_boundary_rows() {
        // 하루 = 1440분. 학습 1/1, 검증 1/2, 테스트 1/3
        long day = 1440 * M;
        long base = SplitConfig.start(java.time.LocalDate.of(2026, 1, 1)).toEpochMilli();
        long[] time = {base, base + day - M, base + day, base + 2 * day + 5 * M, base + 3 * day};
        byte[] status = {LabelOutcome.TARGET.code(), LabelOutcome.TIMEOUT.code(), LabelOutcome.GAP.code(),
                LabelOutcome.STOP.code(), LabelOutcome.STOP.code()};
        long[] end = {base + 10 * M, base + day + 30 * M, 0, base + 2 * day + 20 * M, base + 3 * day + M};
        SplitConfig cfg = new SplitConfig(null, null,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDate.of(2026, 1, 2), java.time.LocalDate.of(2026, 1, 2),
                java.time.LocalDate.of(2026, 1, 3), java.time.LocalDate.of(2026, 1, 3), 0);
        java.time.Instant from = java.time.Instant.ofEpochMilli(base);
        java.time.Instant to = java.time.Instant.ofEpochMilli(base + 4 * day);
        assertThat(cfg.problems(from, to)).isEmpty();
        SplitCalculator.Result r = SplitCalculator.assign(time, status, end, new int[]{3, 0, 0, 0, 0},
                SplitCalculator.Bounds.of(cfg, from, to));
        assertThat(r.segment).containsExactly(1, 1, 2, 3, 0);
        assertThat(SplitCalculator.Reason.of(r.reason[0])).isEqualTo(SplitCalculator.Reason.OK);
        assertThat(SplitCalculator.Reason.of(r.reason[1])).isEqualTo(SplitCalculator.Reason.BOUNDARY);
        assertThat(SplitCalculator.Reason.of(r.reason[2])).isEqualTo(SplitCalculator.Reason.GAP);
        assertThat(SplitCalculator.Reason.of(r.reason[3])).isEqualTo(SplitCalculator.Reason.OK);
        assertThat(SplitCalculator.Reason.of(r.reason[4])).isEqualTo(SplitCalculator.Reason.UNASSIGNED);
        assertThat(r.stats.get(SplitCalculator.TRAIN).validWithNullInputs).isEqualTo(1);

        SplitConfig overlap = new SplitConfig(null, null,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 2),
                java.time.LocalDate.of(2026, 1, 2), java.time.LocalDate.of(2026, 1, 2),
                java.time.LocalDate.of(2026, 1, 3), java.time.LocalDate.of(2026, 1, 3), 0);
        assertThat(overlap.problems(from, to)).anyMatch(p -> p.contains("검증 구간은 학습 구간이 끝난 뒤"));
    }
}
