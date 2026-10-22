package com.mch.strategy.candle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.mch.strategy.collect.CandleSeries;

class CandleTest {

    static final long T0 = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli();
    static final long MIN = 60_000L;

    @Test
    void aggregates_to_5_minute_bars_on_utc_boundaries() {
        // 00:00~00:06 중 00:02 는 비어 있음
        long[] t = {T0, T0 + MIN, T0 + 3 * MIN, T0 + 4 * MIN, T0 + 5 * MIN, T0 + 6 * MIN};
        double[] o = {10, 11, 12, 13, 14, 15};
        double[] h = {11, 15, 13, 14, 16, 16};
        double[] l = {9, 10, 8, 12, 13, 14};
        double[] c = {11, 12, 13, 14, 15, 16};
        double[] v = {1, 1, 1, 1, 2, 2};
        double[] nan = {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        double[] tc = {5, 5, 5, 5, Double.NaN, 1};
        CandleSeries s = new CandleSeries(t, o, h, l, c, v, v, tc, nan);

        CandleSeries five = CandleAggregator.aggregate(s, 5);
        assertThat(five.time()).containsExactly(T0, T0 + 5 * MIN);
        assertThat(five.open()).containsExactly(10, 14);
        assertThat(five.high()).containsExactly(15, 16);
        assertThat(five.low()).containsExactly(8, 13);
        assertThat(five.close()).containsExactly(14, 16);
        assertThat(five.volume()).containsExactly(4, 4);
        assertThat(five.tradeCount()).containsExactly(20, 1);
        assertThat(five.takerBuyVolume()[0]).isNaN();
    }

    @Test
    void align_matches_same_times_only() {
        long[] base = {1, 2, 3, 5};
        long[] other = {2, 3, 4, 5};
        double[] values = {20, 30, 40, 50};
        double[] aligned = SeriesAlign.align(base, other, values);
        assertThat(aligned[0]).isNaN();
        assertThat(aligned[1]).isEqualTo(20);
        assertThat(aligned[2]).isEqualTo(30);
        assertThat(aligned[3]).isEqualTo(50);
    }

    @Test
    void quality_finds_gaps_including_edges() {
        Instant from = Instant.ofEpochMilli(T0);
        Instant to = Instant.ofEpochMilli(T0 + 10 * MIN);
        // 00:00 비어 있음, 00:04~00:05 비어 있음, 00:09 비어 있음
        long[] t = {T0 + MIN, T0 + 2 * MIN, T0 + 3 * MIN, T0 + 6 * MIN, T0 + 7 * MIN, T0 + 8 * MIN};
        QualityChecker.Result r = QualityChecker.check(t, from, to);
        assertThat(r.expectedMinutes()).isEqualTo(10);
        assertThat(r.actualMinutes()).isEqualTo(6);
        assertThat(r.gapCount()).isEqualTo(3);
        assertThat(r.missingMinutes()).isEqualTo(4);
        assertThat(r.gaps().get(0).from()).isEqualTo(from);
        assertThat(r.gaps().get(1).minutes()).isEqualTo(2);
    }
}
