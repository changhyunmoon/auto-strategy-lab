package com.mch.strategy.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class WalkForwardTest {

    static final long M = 60_000L;

    /** 0~99분 탐색(학습 0~59 · 검증 60~79 · 테스트 80~99) */
    static long[] time = new long[100];
    static byte[] split = new byte[100];

    static {
        for (int i = 0; i < 100; i++) {
            time[i] = i * M;
            split[i] = (byte) (i < 60 ? 0 : i < 80 ? 1 : 2);
        }
    }

    @Test
    void expanding_folds_cover_exploration_tail_and_purge_before_each_block() {
        List<WalkForward.Fold> folds = WalkForward.plan(time, split, M, 2, 2, 0.5, null);
        assertThat(folds).hasSize(2);
        WalkForward.Fold f1 = folds.get(0), f2 = folds.get(1);
        // 탐색 0~80분, 첫 학습 50% → 검증은 40분부터 20분씩
        assertThat(f1.valFrom()).isEqualTo(40 * M);
        assertThat(f2.valFrom()).isEqualTo(60 * M);
        // 예측 기간 2봉: 학습 행은 t + 3봉 <= 검증 시작 → 37분까지
        assertThat(time[f1.trainIdx()[f1.trainIdx().length - 1]]).isEqualTo(37 * M);
        assertThat(f1.trainIdx()[0]).isEqualTo(0);
        assertThat(f2.trainIdx()).hasSize(58);
        // 테스트 구간 행은 어디에도 쓰지 않는다
        assertThat(java.util.Arrays.stream(f2.valIdx()).allMatch(i -> split[i] < 2)).isTrue();
        assertThat(f1.valIdx().length + f2.valIdx().length).isEqualTo(40);
    }

    @Test
    void rolling_window_limits_training_to_recent_days() {
        long[] t = new long[10];
        byte[] s = new byte[10];
        for (int i = 0; i < 10; i++) {
            t[i] = i * 86_400_000L;
        }
        List<WalkForward.Fold> folds = WalkForward.plan(t, s, 86_400_000L, 1, 2, 0.6, 3);
        WalkForward.Fold last = folds.get(1);
        assertThat(java.util.Arrays.stream(last.trainIdx()).allMatch(i -> t[i] >= last.valFrom() - 3 * 86_400_000L)).isTrue();
    }

    @Test
    void too_many_folds_fail_with_clear_message() {
        assertThatThrownBy(() -> WalkForward.plan(time, split, M, 1, 12, 0.9, null))
                .hasMessageContaining("폴드");
    }
}
