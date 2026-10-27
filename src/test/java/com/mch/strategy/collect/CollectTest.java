package com.mch.strategy.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mch.strategy.collect.source.BinanceKlines;
import com.mch.strategy.collect.source.Candle1m;

class CollectTest {

    @Test
    void binance_csv_handles_millis_micros_and_header() {
        String csv = """
                open_time,open,high,low,close,volume,close_time,quote_volume,count,taker_buy_volume,taker_buy_quote_volume,ignore
                1735689600000,93576.00,93610.93,93537.50,93610.93,8.21827000,1735689659999,768978.95,1435,3.67893000,344238.32,0
                1735689660000000,93610.93,93652.00,93606.42,93652.00,5.20000000,1735689719999999,486865.12,900,2.10000000,196600.00,0
                """;
        List<Candle1m> candles = BinanceKlines.parseCsv(csv);
        assertThat(candles).hasSize(2);
        assertThat(candles.get(0).openTime()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(candles.get(1).openTime()).isEqualTo(Instant.parse("2025-01-01T00:01:00Z"));
        assertThat(candles.get(0).tradeCount()).isEqualTo(1435);
        assertThat(candles.get(0).takerBuyVolume()).isEqualByComparingTo("3.67893");
        assertThat(candles.get(1).quoteVolume()).isEqualByComparingTo("486865.12");
    }

    @Test
    void binance_rest_row() {
        Candle1m c = BinanceKlines.fromRestRow(List.of(1735689600000L, "1", "2", "0.5", "1.5", "10", 1735689659999L,
                "15", 7, "4", "6", "0"));
        assertThat(c.openTime()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(c.tradeCount()).isEqualTo(7);
    }

    @Test
    void already_collected_merges_successful_ranges() throws Exception {
        CollectLogRepository repo = mock(CollectLogRepository.class);
        when(repo.findByMarketIdAndStatusOrderByRangeFromAsc(anyLong(), any())).thenReturn(List.of(
                log("2026-10-01T00:00:00Z", "2026-10-05T00:00:00Z"),
                log("2026-10-04T00:00:00Z", "2026-10-10T00:00:00Z")));
        CollectService service = new CollectService(List.of(), mock(CandleRepository.class), repo);

        assertThat(service.alreadyCollected(1, Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"))).isTrue();
        assertThat(service.alreadyCollected(1, Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-11T00:00:00Z"))).isFalse();
        assertThat(service.alreadyCollected(1, Instant.parse("2026-09-30T00:00:00Z"), Instant.parse("2026-10-03T00:00:00Z"))).isFalse();
    }

    @Test
    void collected_and_missing_ranges() throws Exception {
        CollectLogRepository repo = mock(CollectLogRepository.class);
        when(repo.findByMarketIdAndStatusOrderByRangeFromAsc(anyLong(), any())).thenReturn(List.of(
                log("2026-10-01T00:00:00Z", "2026-10-05T00:00:00Z"),
                log("2026-10-03T00:00:00Z", "2026-10-06T00:00:00Z"),
                log("2026-10-06T00:00:00Z", "2026-10-07T00:00:00Z"),   // 맞닿으면 하나로
                log("2026-10-10T00:00:00Z", "2026-10-12T00:00:00Z")));
        CollectService service = new CollectService(List.of(), mock(CandleRepository.class), repo);

        assertThat(service.collectedRanges(1)).containsExactly(
                new CollectService.Range(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-07T00:00:00Z")),
                new CollectService.Range(Instant.parse("2026-10-10T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z")));

        // 앞 · 사이 · 뒤가 빠진 경우
        assertThat(service.missingRanges(1, Instant.parse("2026-09-30T00:00:00Z"), Instant.parse("2026-10-14T00:00:00Z"))).containsExactly(
                new CollectService.Range(Instant.parse("2026-09-30T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")),
                new CollectService.Range(Instant.parse("2026-10-07T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z")),
                new CollectService.Range(Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-14T00:00:00Z")));
        // 받은 구간 안
        assertThat(service.missingRanges(1, Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-06T12:00:00Z"))).isEmpty();
        // 받은 구간 사이에 통째로 들어간 경우
        assertThat(service.missingRanges(1, Instant.parse("2026-10-08T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"))).containsExactly(
                new CollectService.Range(Instant.parse("2026-10-08T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z")));
    }

    private static CollectLog log(String from, String to) throws Exception {
        CollectLog log = CollectLog.start(1, Instant.parse(from), Instant.parse(to));
        log.succeed(1);
        Field f = CollectLog.class.getDeclaredField("status");
        f.setAccessible(true);
        assertThat(f.get(log)).isEqualTo(CollectLog.SUCCESS);
        return log;
    }
}
