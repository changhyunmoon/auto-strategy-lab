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

    static Backtester.Input withRange(float[] close, float[] high, float[] low, int[] pred) {
        Backtester.Input b = oneAsset(close, pred);
        return new Backtester.Input(b.time(), b.asset(), close, high, low, pred, null, b.assets(), M);
    }

    static Backtester.Settings limit(double fee, double slip, int hold, int wait) {
        return new Backtester.Settings(fee, slip, false, 0.5, 0.5, hold, false, true, Backtester.LIMIT, wait);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> orders(Map<String, Object> r) {
        return (Map<String, Object>) total(r).get("orders");
    }

    @Test
    void limit_order_fills_only_when_price_crosses_and_pays_fee_without_slippage() {
        // 0봉 종가 100 에 매수 주문 → 1봉 저가 99 < 100 이라 체결 → 1봉 보유 후 2봉 종가 110 에 매도 주문 → 3봉 고가 111 > 110 체결
        float[] close = {100, 101, 110, 110};
        float[] high = {101, 102, 111, 111};
        float[] low = {99, 99, 105, 109};
        Map<String, Object> r = Backtester.run(withRange(close, high, low, new int[]{2, 1, 1, 1}), limit(0.05, 0.05, 1, 1), true);
        assertThat(total(r).get("trades")).isEqualTo(1);
        double expected = (0.9995 * 1.10 * 0.9995 - 1) * 100; // 슬리피지 0.05% 는 빠지지 않는다
        assertThat(((Number) total(r).get("netReturnPct")).doubleValue()).isCloseTo(expected, offset(1e-6));
        assertThat(orders(r).get("entryFilled")).isEqualTo(1);
        assertThat(orders(r).get("exitFilled")).isEqualTo(1);
    }

    @Test
    void limit_entry_is_cancelled_when_price_only_touches() {
        float[] close = {100, 103, 104, 105};
        float[] high = {101, 104, 105, 106};
        float[] low = {99, 100, 102, 104}; // 1봉 저가가 100 에 닿기만 함 → 미체결
        Map<String, Object> r = Backtester.run(withRange(close, high, low, new int[]{2, 1, 1, 1}), limit(0.05, 0, 1, 1), true);
        assertThat(total(r).get("trades")).isEqualTo(0);
        assertThat(orders(r).get("entryMissed")).isEqualTo(1);
        assertThat(((Number) total(r).get("netReturnPct")).doubleValue()).isCloseTo(0, offset(1e-9));
    }

    @Test
    void unfilled_limit_exit_falls_back_to_market_with_slippage() {
        float[] close = {100, 101, 110, 108};
        float[] high = {101, 102, 111, 110}; // 3봉 고가가 매도가 110 을 넘지 못함
        float[] low = {99, 99, 105, 107};
        Map<String, Object> r = Backtester.run(withRange(close, high, low, new int[]{2, 1, 1, 1}), limit(0.05, 0.05, 1, 1), true);
        double expected = (0.9995 * 1.08 * 0.999 - 1) * 100; // 진입은 지정가(수수료만), 청산은 시장가(수수료 + 슬리피지)
        assertThat(((Number) total(r).get("netReturnPct")).doubleValue()).isCloseTo(expected, offset(1e-6));
        assertThat(orders(r).get("exitFallback")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void compare_lists_market_with_and_without_slippage_and_limit() {
        float[] close = {100, 101, 110, 110};
        float[] high = {101, 102, 111, 111};
        float[] low = {99, 99, 105, 109};
        Map<String, Object> r = Backtester.run(withRange(close, high, low, new int[]{2, 1, 1, 1}),
                new Backtester.Settings(0.05, 0.02, false, 0.5, 0.5, 1, false, true), true);
        List<Map<String, Object>> cmp = (List<Map<String, Object>>) r.get("compare");
        assertThat(cmp).extracting(m -> m.get("key")).containsExactly("market", "marketNoSlip", "limit");
        assertThat(cmp.get(0).get("selected")).isEqualTo(true);
        double withSlip = ((Number) cmp.get(0).get("netReturnPct")).doubleValue();
        double noSlip = ((Number) cmp.get(1).get("netReturnPct")).doubleValue();
        assertThat(noSlip).isGreaterThan(withSlip);
        assertThat((List<?>) cmp.get(2).get("netPct")).hasSameSizeAs((List<?>) ((Map<String, Object>) r.get("curve")).get("time"));
    }

    @Test
    void limit_without_high_low_is_rejected_and_compare_skips_it() {
        float[] close = {100, 101};
        Backtester.Input in = oneAsset(close, new int[]{2, 1});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Backtester.run(in, limit(0.05, 0, 1, 1), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("다시 만든");
        Map<String, Object> r = Backtester.run(in, new Backtester.Settings(0.05, 0.02, false, 0.5, 0.5, 1, false, true), true);
        assertThat(r.get("limitAvailable")).isEqualTo(false);
        assertThat((List<?>) r.get("compare")).hasSize(2);
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
