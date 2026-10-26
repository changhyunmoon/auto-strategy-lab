package com.mch.strategy.trainset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import org.junit.jupiter.api.Test;

class TrainingRowsTest {

    static final long M = 60_000L;

    static LabelSpec fixed(String method, int horizon, double pct) {
        return new LabelSpec(method, horizon, LabelSpec.FIXED, pct, 1, 60, 0.05, 0.02, 0);
    }

    static TrainingRows.Calculator calc(long[] time, double[] close) {
        return new TrainingRows.Calculator(time, close, close, close, M);
    }

    @Test
    void label_uses_threshold_inclusive() {
        assertThat(TrainingRows.label(0.3, 0.3)).isEqualTo(TrainingRows.UP);
        assertThat(TrainingRows.label(-0.3, 0.3)).isEqualTo(TrainingRows.DOWN);
        assertThat(TrainingRows.label(0.2999, 0.3)).isEqualTo(TrainingRows.NEUTRAL);
    }

    @Test
    void next_bar_plan_assigns_split_and_excludes_rows_whose_next_bar_is_missing_or_crosses_boundary() {
        // 0..9분 봉, 5분 봉은 빠짐(빈 구간). 학습 [0,4분) · 검증 [4분,10분)
        long[] time = {0, M, 2 * M, 3 * M, 4 * M, 6 * M, 7 * M, 8 * M, 9 * M};
        double[] close = {100, 101, 100, 100, 100, 100, 100.5, 100, 100};
        long[][] ranges = {{0, 4 * M}, {4 * M, 10 * M}, {20 * M, 30 * M}};
        TrainingRows.Plan[] p = TrainingRows.plan(calc(time, close), ranges, fixed(LabelSpec.FORWARD_RETURN, 1, 0.3));

        assertThat(p[0].split()).isEqualTo(0);
        assertThat(p[0].forwardPct()).isCloseTo(1.0, offset(1e-9));
        assertThat(p[0].label()).isEqualTo(TrainingRows.UP);
        assertThat(p[0].previousLabel()).isNaN();
        assertThat(p[1].previousLabel()).isEqualTo(TrainingRows.UP); // 직전 1봉 +1%
        assertThat(p[3].excludedReason()).isEqualTo(TrainingRows.CROSSES_BOUNDARY);
        assertThat(p[4].excludedReason()).isEqualTo(TrainingRows.NO_NEXT_BAR);
        assertThat(p[5].split()).isEqualTo(1);
        assertThat(p[8].excludedReason()).isEqualTo(TrainingRows.NO_NEXT_BAR);
    }

    @Test
    void horizon_n_needs_n_contiguous_bars_and_purges_n_bars_before_boundary() {
        long[] time = new long[10];
        double[] close = new double[10];
        for (int i = 0; i < 10; i++) {
            time[i] = i * M;
            close[i] = 100 + i;
        }
        long[][] ranges = {{0, 6 * M}, {6 * M, 10 * M}, {20 * M, 30 * M}};
        TrainingRows.Plan[] p = TrainingRows.plan(calc(time, close), ranges, fixed(LabelSpec.FORWARD_RETURN, 3, 0.5));
        // 학습 끝 6분: 정답이 3봉 뒤 종가(끝나는 시각 t+4분)를 쓰므로 t <= 2분 만 남는다
        assertThat(p[2].split()).isEqualTo(0);
        assertThat(p[3].excludedReason()).isEqualTo(TrainingRows.CROSSES_BOUNDARY);
        assertThat(p[0].forwardPct()).isCloseTo(3.0, offset(1e-9));
        // 데이터 끝 3봉은 정답을 만들 수 없다
        assertThat(p[7].excludedReason()).isEqualTo(TrainingRows.NO_NEXT_BAR);
    }

    @Test
    void triple_barrier_uses_first_touch_and_marks_same_bar_touch_ambiguous() {
        long[] time = {0, M, 2 * M, 3 * M};
        double[] close = {100, 100, 100, 100};
        double[] high = {100, 100.2, 101.5, 100};
        double[] low = {100, 99.8, 100, 98};
        TrainingRows.Calculator c = new TrainingRows.Calculator(time, close, high, low, M);
        long[][] ranges = {{0, 10 * M}, {10 * M, 20 * M}, {20 * M, 30 * M}};
        // ±1%: 2번째 봉에서 고가 101.5 → 상승 (3번째 봉의 저가 98 보다 먼저)
        TrainingRows.Plan[] p = TrainingRows.plan(c, ranges, fixed(LabelSpec.TRIPLE_BARRIER, 3, 1.0));
        assertThat(p[0].label()).isEqualTo(TrainingRows.UP);
        assertThat(p[0].ambiguous()).isFalse();

        double[] high2 = {100, 101.5, 100, 100};
        double[] low2 = {100, 98.5, 100, 100};
        TrainingRows.Plan[] q = TrainingRows.plan(new TrainingRows.Calculator(time, close, high2, low2, M), ranges,
                fixed(LabelSpec.TRIPLE_BARRIER, 2, 1.0));
        assertThat(q[0].label()).isEqualTo(TrainingRows.NEUTRAL);
        assertThat(q[0].ambiguous()).isTrue();
    }

    @Test
    void volatility_threshold_uses_only_past_returns_and_excludes_rows_without_history() {
        int n = 40;
        long[] time = new long[n];
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            time[i] = i * M;
            close[i] = 100 * (i % 2 == 0 ? 1 : 1.01); // ±1% 정도 오르내림
        }
        LabelSpec vol = new LabelSpec(LabelSpec.FORWARD_RETURN, 1, LabelSpec.VOLATILITY, 0, 0.5, 20, 0, 0, 0);
        long[][] ranges = {{0, 100 * M}, {100 * M, 200 * M}, {200 * M, 300 * M}};
        TrainingRows.Plan[] p = TrainingRows.plan(calc(time, close), ranges, vol);
        assertThat(p[3].excludedReason()).isEqualTo(TrainingRows.NO_VOLATILITY);
        assertThat(p[30].split()).isEqualTo(0);
        // 1봉 로그 수익률 크기가 약 1% 라 표준편차 약 1% → 기준 약 0.5%
        assertThat(p[30].thresholdPct()).isCloseTo(0.5, offset(0.05));
    }

    @Test
    void cost_threshold_is_round_trip_cost_plus_margin() {
        LabelSpec cost = new LabelSpec(LabelSpec.FORWARD_RETURN, 1, LabelSpec.COST, 0, 1, 60, 0.05, 0.02, 0.1);
        assertThat(cost.threshold(0)).isCloseTo(0.24, offset(1e-12));
        assertThat(cost.alternatives()).hasSize(LabelSpec.MARGINS.size());
    }

    @Test
    void align_uses_only_bars_already_closed_at_decision_end() {
        long[] oneMin = {0, M, 2 * M, 3 * M, 4 * M, 5 * M, 6 * M, 7 * M, 8 * M, 9 * M, 10 * M};
        long[] fiveMin = {0, 5 * M, 10 * M};
        int[] idx = TrainingRows.alignCompleted(oneMin, M, fiveMin, 5 * M);
        assertThat(idx[0]).isEqualTo(-1);
        assertThat(idx[3]).isEqualTo(-1);
        assertThat(idx[4]).isEqualTo(0);
        assertThat(idx[9]).isEqualTo(1);
    }

    @Test
    void align_does_not_attach_stale_bar_after_gap() {
        int[] idx = TrainingRows.alignCompleted(new long[]{20 * M}, M, new long[]{0, 5 * M}, 5 * M);
        assertThat(idx[0]).isEqualTo(-1);
    }
}
