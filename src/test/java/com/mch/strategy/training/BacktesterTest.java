package com.mch.strategy.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BacktesterTest {

    static final long M = 60_000L;

    static Backtester.Input oneAsset(float[] close, int[] pred) {
        long[] time = new long[close.length];
        for (int i = 0; i < time.length; i++) {
            time[i] = i * M;
        }
        return new Backtester.Input(time, new int[close.length], close, pred, null, List.of("BTC"), M);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> total(Map<String, Object> r) {
        return (Map<String, Object>) r.get("total");
    }

    @Test
    void long_trade_pays_fee_and_slippage_on_both_sides() {
        // 100 에서 롱 진입, 2봉 보유 후 110 에 청산. 한쪽 비용 0.1% (수수료 0.05 + 슬리피지 0.05)
        float[] close = {100, 105, 110, 110};
        int[] pred = {2, 1, 1, 1};
        Map<String, Object> r = Backtester.run(oneAsset(close, pred), new Backtester.Settings(0.05, 0.05, false, 0.5, 0.5, 2, false, true), true);
        double expected = (0.999 * 1.10 * 0.999 - 1) * 100;
        assertThat(((Number) total(r).get("netReturnPct")).doubleValue()).isCloseTo(expected, offset(1e-6));
        assertThat(((Number) total(r).get("grossReturnPct")).doubleValue()).isCloseTo(10.0, offset(1e-6));
        assertThat(total(r).get("trades")).isEqualTo(1);
        assertThat(((Number) total(r).get("buyHoldPct")).doubleValue()).isCloseTo(10.0, offset(1e-6));
    }

    @Test
    void short_profits_when_price_falls_and_opposite_signal_flips_position() {
        // 숏 진입(100) → 반대 신호(90)에서 숏 청산(+10%) 후 롱 진입 → 끝(99)에서 롱 청산(+10%)
        float[] close = {100, 95, 90, 99};
        int[] pred = {0, 1, 2, 1};
        Map<String, Object> r = Backtester.run(oneAsset(close, pred), new Backtester.Settings(0, 0, false, 0.5, 0.5, 10, false, true), true);
        assertThat(total(r).get("trades")).isEqualTo(2);
        assertThat(total(r).get("shortTrades")).isEqualTo(1L);
        assertThat(((Number) total(r).get("netReturnPct")).doubleValue()).isCloseTo((1.10 * 1.10 - 1) * 100, offset(1e-6));
    }

    @Test
    void probability_rule_uses_thresholds_and_sweep_is_reported() {
        float[] close = {100, 101, 102, 103, 104};
        long[] time = {0, M, 2 * M, 3 * M, 4 * M};
        double[][] proba = {{0.1, 0.3, 0.6}, {0.2, 0.6, 0.2}, {0.1, 0.5, 0.4}, {0.3, 0.4, 0.3}, {0.2, 0.6, 0.2}};
        Backtester.Input in = new Backtester.Input(time, new int[5], close, new int[]{2, 1, 1, 1, 1}, proba, List.of("BTC"), M);
        Map<String, Object> high = Backtester.run(in, new Backtester.Settings(0, 0, true, 0.7, 0.7, 1, false, true), true);
        assertThat(total(high).get("trades")).isEqualTo(0);
        Map<String, Object> low = Backtester.run(in, new Backtester.Settings(0, 0, true, 0.35, 0.35, 1, false, true), true);
        assertThat((Integer) total(low).get("trades")).isGreaterThan(0);
        assertThat(low).containsKey("sweep");
    }

    @Test
    void max_drawdown_is_measured_on_marked_to_market_equity() {
        float[] close = {100, 120, 90, 100};
        int[] pred = {2, 2, 2, 2};
        Map<String, Object> r = Backtester.run(oneAsset(close, pred), new Backtester.Settings(0, 0, false, 0.5, 0.5, 100, true, true), true);
        // 120 → 90 이면 25% 하락
        assertThat(((Number) total(r).get("maxDrawdownPct")).doubleValue()).isCloseTo(25.0, offset(1e-6));
    }
}
