package com.mch.strategy.collect;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mch.strategy.collect.source.Candle1m;

import lombok.RequiredArgsConstructor;

/** candle_1m 테이블. 수백만 행을 다루므로 JPA 대신 JdbcTemplate 배치를 쓴다. */
@Repository
@RequiredArgsConstructor
public class CandleRepository {

    private final JdbcTemplate jdbc;

    /** 이미 있는 봉(같은 마켓·시각)은 INSERT IGNORE 로 건너뛴다. */
    public void insertIgnore(long marketId, List<Candle1m> candles) {
        if (candles.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT IGNORE INTO candle_1m
                  (market_id, open_time, open, high, low, close, volume, quote_volume,
                   trade_count, taker_buy_volume, taker_buy_quote)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, candles, 1000, (ps, c) -> {
            ps.setLong(1, marketId);
            ps.setTimestamp(2, Timestamp.from(c.openTime()));
            ps.setBigDecimal(3, c.open());
            ps.setBigDecimal(4, c.high());
            ps.setBigDecimal(5, c.low());
            ps.setBigDecimal(6, c.close());
            ps.setBigDecimal(7, c.volume());
            ps.setBigDecimal(8, c.quoteVolume());
            ps.setObject(9, c.tradeCount());
            ps.setBigDecimal(10, c.takerBuyVolume());
            ps.setBigDecimal(11, c.takerBuyQuote());
        });
    }

    /** [from, to) 구간의 1분봉을 시간순 배열로 읽는다. */
    public CandleSeries load(long marketId, Instant from, Instant to) {
        List<Object[]> rows = new ArrayList<>();
        jdbc.query("""
                SELECT open_time, open, high, low, close, volume, quote_volume, trade_count, taker_buy_volume
                  FROM candle_1m
                 WHERE market_id = ? AND open_time >= ? AND open_time < ?
                 ORDER BY open_time
                """, rs -> {
            rows.add(new Object[]{
                    rs.getTimestamp(1).toInstant().toEpochMilli(),
                    rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                    rs.getDouble(6), rs.getDouble(7),
                    nullableDouble(rs.getObject(8)), nullableDouble(rs.getBigDecimal(9))
            });
        }, marketId, Timestamp.from(from), Timestamp.from(to));

        int n = rows.size();
        long[] time = new long[n];
        double[][] cols = new double[8][n];
        for (int i = 0; i < n; i++) {
            Object[] r = rows.get(i);
            time[i] = (Long) r[0];
            for (int c = 0; c < 8; c++) {
                cols[c][i] = (Double) r[c + 1];
            }
        }
        return new CandleSeries(time, cols[0], cols[1], cols[2], cols[3], cols[4], cols[5], cols[6], cols[7]);
    }

    /** 마켓별로 DB에 있는 첫·마지막 봉 시각과 개수. */
    public List<Coverage> coverage() {
        return jdbc.query("""
                SELECT market_id, MIN(open_time), MAX(open_time), COUNT(*)
                  FROM candle_1m GROUP BY market_id
                """, (rs, i) -> new Coverage(
                rs.getLong(1),
                rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant(),
                rs.getLong(4)));
    }

    /** 날짜(UTC)별 봉 개수. 시각화 품질 탭에서 날짜별 누락 분 수를 계산할 때 쓴다. */
    public Map<LocalDate, Integer> dailyCounts(long marketId, Instant from, Instant to) {
        Map<LocalDate, Integer> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DATE(open_time) AS d, COUNT(*) FROM candle_1m
                 WHERE market_id = ? AND open_time >= ? AND open_time < ?
                 GROUP BY d ORDER BY d
                """, rs -> {
            result.put(rs.getDate(1).toLocalDate(), rs.getInt(2));
        }, marketId, Timestamp.from(from), Timestamp.from(to));
        return result;
    }

    private static double nullableDouble(Object value) {
        if (value == null) {
            return Double.NaN;
        }
        if (value instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        return ((Number) value).doubleValue();
    }

    public record Coverage(long marketId, Instant first, Instant last, long count) {
    }
}
