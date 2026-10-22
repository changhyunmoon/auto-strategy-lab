package com.mch.strategy.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class TaTest {

    @Test
    void sma_and_ema() {
        double[] x = {1, 2, 3, 4, 5};
        double[] sma = Ta.sma(x, 3);
        assertThat(sma[1]).isNaN();
        assertThat(sma[2]).isEqualTo(2.0);
        assertThat(sma[4]).isEqualTo(4.0);

        double[] ema = Ta.ema(x, 3); // k = 0.5
        assertThat(ema[1]).isNaN();
        // 1 → 1.5 → 2.25 → 3.125 → 4.0625
        assertThat(ema[2]).isCloseTo(2.25, within(1e-12));
        assertThat(ema[4]).isCloseTo(4.0625, within(1e-12));
    }

    @Test
    void sma_skips_windows_with_nan() {
        double[] sma = Ta.sma(new double[]{1, Double.NaN, 3, 4, 5}, 2);
        assertThat(sma[2]).isNaN();
        assertThat(sma[3]).isEqualTo(3.5);
    }

    @Test
    void rsi_extremes() {
        double[] up = new double[30];
        double[] flat = new double[30];
        for (int i = 0; i < 30; i++) {
            up[i] = 100 + i;
            flat[i] = 100;
        }
        assertThat(Ta.rsi(up, 14)[13]).isNaN();
        assertThat(Ta.rsi(up, 14)[29]).isEqualTo(100.0);
        assertThat(Ta.rsi(flat, 14)[29]).isEqualTo(50.0);
    }

    @Test
    void rsi_matches_wilder_reference() {
        // StockCharts RSI 설명 자료의 예제 계열. 자료의 표는 상승·하락 폭을 소수 둘째 자리로 반올림해 70.53 이 나오고,
        // 반올림 없이 정확히 계산하면 70.464 다.
        double[] close = {44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08,
                45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64};
        double[] rsi = Ta.rsi(close, 14);
        assertThat(rsi[14]).isCloseTo(70.464, within(0.001));
        assertThat(rsi[15]).isLessThan(rsi[14]); // 46.28 → 46.00 하락
    }

    @Test
    void atr_uses_true_range() {
        double[] high = {10, 12, 11}, low = {9, 10, 8}, close = {9.5, 11, 9};
        double[] atr = Ta.atr(high, low, close, 2);
        // TR = [1, 2.5, 3] → 첫 ATR = 1.75, 다음 = (1.75 + 3) / 2
        assertThat(atr[1]).isEqualTo(1.75);
        assertThat(atr[2]).isEqualTo(2.375);
    }

    @Test
    void cross_state_counts_bars_since_sign_change() {
        double[][] s = Ta.crossState(new double[]{Double.NaN, 1, 2, -1, -3, 0, 2});
        assertThat(s[0]).containsExactly(Double.NaN, 1, 1, -1, -1, -1, 1);
        assertThat(s[1]).containsExactly(Double.NaN, 0, 1, 0, 1, 2, 0);
    }

    @Test
    void rolling_corr_of_identical_series_is_one() {
        double[] a = {1, 3, 2, 5, 4, 6};
        double[] neg = {-1, -3, -2, -5, -4, -6};
        assertThat(Ta.rollingCorr(a, a, 4)[5]).isCloseTo(1.0, within(1e-9));
        assertThat(Ta.rollingCorr(a, neg, 4)[5]).isCloseTo(-1.0, within(1e-9));
    }
}
