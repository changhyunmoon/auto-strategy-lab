package com.mch.strategy.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class TaTest {

    static final double E = 1e-9;

    @Test
    void sma_wma_vwma() {
        double[] x = {1, 2, 3, 4, 5};
        assertThat(Ta.sma(x, 3)).containsExactly(new double[]{Double.NaN, Double.NaN, 2, 3, 4}, within(E));
        // WMA 3 at t=2: (1×1 + 2×2 + 3×3)/6 = 14/6
        assertThat(Ta.wma(x, 3)[2]).isCloseTo(14.0 / 6, within(E));
        double[] v = {1, 1, 2, 0, 0};
        // VWMA 2 at t=2: (2×1 + 3×2)/3
        assertThat(Ta.vwma(x, v, 2)[2]).isCloseTo(8.0 / 3, within(E));
        // 거래량 합 0 → null
        assertThat(Ta.vwma(x, v, 2)[4]).isNaN();
    }

    @Test
    void ema_starts_with_sma_seed() {
        double[] x = {2, 4, 6, 8};
        double[] e = Ta.ema(x, 3);
        assertThat(e[1]).isNaN();
        assertThat(e[2]).isCloseTo(4, within(E));
        assertThat(e[3]).isCloseTo(0.5 * 8 + 0.5 * 4, within(E));
    }

    @Test
    void rsi_wilder_and_edge_cases() {
        double[] up = {1, 2, 3, 4};
        assertThat(Ta.rsi(up, 3)[2]).isNaN();
        assertThat(Ta.rsi(up, 3)[3]).isEqualTo(100);
        double[] flat = {5, 5, 5, 5};
        assertThat(Ta.rsi(flat, 3)[3]).isEqualTo(50);
        double[] down = {4, 3, 2, 1};
        assertThat(Ta.rsi(down, 3)[3]).isEqualTo(0);
        // 상승 2, 하락 1 → avg_gain 2/2, avg_loss 1/2 → RS 2 → 66.67
        double[] mix = {10, 12, 11};
        assertThat(Ta.rsi(mix, 2)[2]).isCloseTo(100 - 100 / 3.0, within(E));
    }

    @Test
    void atr_wilder() {
        double[] h = {10, 12, 11};
        double[] l = {8, 9, 7};
        double[] c = {9, 11, 8};
        double[] tr = Ta.trueRange(h, l, c);
        assertThat(tr).containsExactly(2, 3, 4);
        double[] atr = Ta.atr(tr, 2);
        assertThat(atr[0]).isNaN();
        assertThat(atr[1]).isCloseTo(2.5, within(E));
        assertThat(atr[2]).isCloseTo((2.5 + 4) / 2, within(E));
    }

    @Test
    void volume_family() {
        double[] vol = {1, 1, 1, 4};
        assertThat(Ta.rvol(vol, 3)[3]).isCloseTo(4, within(E));
        assertThat(Ta.rvol(vol, 3)[2]).isNaN();
        double[] zero = {0, 0, 0, 5};
        assertThat(Ta.rvol(zero, 3)[3]).isNaN();
        double[] close = {1, 2, 2, 1};
        assertThat(Ta.obv(close, new double[]{9, 3, 5, 2})).containsExactly(0, 3, 3, 1);
        // CMF: 종가가 고가에서 끝나면 배수 +1
        double[] h = {2, 2}, l = {1, 1}, c = {2, 2}, v = {1, 3};
        assertThat(Ta.cmf(h, l, c, v, 2)[1]).isCloseTo(1, within(E));
        // MFI 음의 흐름 없음 → 100
        double[] th = {1, 2, 3}, tl = {1, 2, 3}, tc = {1, 2, 3}, tv = {1, 1, 1};
        assertThat(Ta.mfi(th, tl, tc, tv, 2)[2]).isEqualTo(100);
        assertThat(Ta.volumeOscillator(new double[]{1, 1, 3}, 1, 3)[2]).isCloseTo(100 * (3 - 5.0 / 3) / (5.0 / 3), within(E));
    }

    @Test
    void stdev_population_and_stoch_rsi() {
        double[] x = {2, 4, 4, 4, 5, 5, 7, 9};
        assertThat(Ta.stdev(x, 8)[7]).isCloseTo(2, within(E));
        assertThat(Ta.stdev(new double[]{3, 3, 3}, 3)[2]).isEqualTo(0);
        double[] rsi = {Double.NaN, 20, 40, 30};
        assertThat(Ta.stochRsi(rsi, 2)[1]).isNaN();
        assertThat(Ta.stochRsi(rsi, 3)[3]).isCloseTo(50, within(E));
    }

    @Test
    void crosses() {
        double[] x = {-1, 0, 1, -1};
        assertThat(Ta.crossUp(x)).containsExactly(new double[]{Double.NaN, 0, 1, 0}, within(E));
        assertThat(Ta.crossDown(x)).containsExactly(new double[]{Double.NaN, 0, 0, 1}, within(E));
    }
}
