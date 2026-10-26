package com.mch.strategy.trainset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrainingRowsTest {

    static final long M = 60_000L;

    @Test
    void label_uses_threshold_inclusive() {
        assertThat(TrainingRows.label(0.3, 0.3)).isEqualTo(TrainingRows.UP);
        assertThat(TrainingRows.label(-0.3, 0.3)).isEqualTo(TrainingRows.DOWN);
        assertThat(TrainingRows.label(0.2999, 0.3)).isEqualTo(TrainingRows.NEUTRAL);
        assertThat(TrainingRows.label(-0.2999, 0.3)).isEqualTo(TrainingRows.NEUTRAL);
    }

    @Test
    void plan_assigns_split_and_excludes_rows_whose_next_bar_is_missing_or_crosses_boundary() {
        // 0..9분 봉, 5분 봉은 빠짐(빈 구간). 학습 [0,4분) · 검증 [4분,10분)
        long[] time = {0, M, 2 * M, 3 * M, 4 * M, 6 * M, 7 * M, 8 * M, 9 * M};
        double[] close = {100, 101, 100, 100, 100, 100, 100.5, 100, 100};
        long[][] ranges = {{0, 4 * M}, {4 * M, 10 * M}, {20 * M, 30 * M}};
        TrainingRows.Plan[] p = TrainingRows.plan(time, close, M, ranges);

        assertThat(p[0].split()).isEqualTo(0);
        assertThat(p[0].forwardPct()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(p[0].prevPct()).isNaN();
        assertThat(p[1].prevPct()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(p[2].split()).isEqualTo(0);
        // 3분 봉의 다음 봉(4분)은 검증 구간 → 학습 끝을 넘는다
        assertThat(p[3].excludedReason()).isEqualTo(TrainingRows.CROSSES_BOUNDARY);
        // 4분 봉의 다음 봉(5분)이 없다
        assertThat(p[4].excludedReason()).isEqualTo(TrainingRows.NO_NEXT_BAR);
        assertThat(p[5].split()).isEqualTo(1);
        assertThat(p[5].prevPct()).isNaN(); // 직전 봉(5분)이 없다
        assertThat(p[7].split()).isEqualTo(1);
        // 마지막 봉은 다음 봉이 없다
        assertThat(p[8].excludedReason()).isEqualTo(TrainingRows.NO_NEXT_BAR);
    }

    @Test
    void plan_marks_rows_outside_all_splits() {
        long[] time = {0, M, 2 * M};
        double[] close = {1, 1, 1};
        long[][] ranges = {{10 * M, 20 * M}, {20 * M, 30 * M}, {30 * M, 40 * M}};
        TrainingRows.Plan[] p = TrainingRows.plan(time, close, M, ranges);
        assertThat(p).allMatch(x -> TrainingRows.OUTSIDE.equals(x.excludedReason()));
    }

    @Test
    void align_uses_only_bars_already_closed_at_decision_end() {
        long[] oneMin = {0, M, 2 * M, 3 * M, 4 * M, 5 * M, 6 * M, 7 * M, 8 * M, 9 * M, 10 * M};
        long[] fiveMin = {0, 5 * M, 10 * M};
        int[] idx = TrainingRows.alignCompleted(oneMin, M, fiveMin, 5 * M);
        // 1분 봉 0~3분은 끝나는 시각(1~4분)에 끝난 5분 봉이 없다
        assertThat(idx[0]).isEqualTo(-1);
        assertThat(idx[3]).isEqualTo(-1);
        // 4분 봉은 5분에 끝나고, 그때 0~5분 봉이 막 끝났다
        assertThat(idx[4]).isEqualTo(0);
        assertThat(idx[8]).isEqualTo(0);
        // 9분 봉은 10분에 끝나고 5~10분 봉이 막 끝났다
        assertThat(idx[9]).isEqualTo(1);
        assertThat(idx[10]).isEqualTo(1);
    }

    @Test
    void align_does_not_attach_stale_bar_after_gap() {
        long[] oneMin = {20 * M};          // 20~21분 봉
        long[] fiveMin = {0, 5 * M};       // 10~15·15~20분 봉이 빠짐
        int[] idx = TrainingRows.alignCompleted(oneMin, M, fiveMin, 5 * M);
        assertThat(idx[0]).isEqualTo(-1);
    }
}
