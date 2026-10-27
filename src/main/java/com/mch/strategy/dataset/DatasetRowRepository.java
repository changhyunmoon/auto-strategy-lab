package com.mch.strategy.dataset;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mch.strategy.common.JsonArrays;

import lombok.RequiredArgsConstructor;

/** dataset_row 테이블. 행이 많으므로 JdbcTemplate 배치로 다룬다. */
@Repository
@RequiredArgsConstructor
public class DatasetRowRepository {

    private final JdbcTemplate jdbc;

    public void insert(long datasetId, long assetId, String interval, List<RowInsert> rows) {
        jdbc.batchUpdate("""
                INSERT INTO dataset_row (dataset_id, asset_id, bar_interval, open_time, is_warmup, feature_values)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows, 1000, (ps, r) -> {
            ps.setLong(1, datasetId);
            ps.setLong(2, assetId);
            ps.setString(3, interval);
            ps.setTimestamp(4, new Timestamp(r.time()));
            ps.setBoolean(5, r.warmup());
            ps.setString(6, r.json());
        });
    }

    public long count(long datasetId, long assetId, String interval, boolean includeWarmup) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dataset_row
                 WHERE dataset_id = ? AND asset_id = ? AND bar_interval = ? AND (? OR is_warmup = FALSE)
                """, Long.class, datasetId, assetId, interval, includeWarmup);
        return n == null ? 0 : n;
    }

    /** 미리보기용 페이지 조회 */
    public List<RowView> page(long datasetId, long assetId, String interval, boolean includeWarmup, long offset, int limit) {
        return jdbc.query("""
                SELECT open_time, is_warmup, feature_values FROM dataset_row
                 WHERE dataset_id = ? AND asset_id = ? AND bar_interval = ? AND (? OR is_warmup = FALSE)
                 ORDER BY open_time
                 LIMIT ? OFFSET ?
                """, (rs, i) -> new RowView(
                rs.getTimestamp(1).toInstant().toEpochMilli(),
                rs.getBoolean(2),
                JsonArrays.read(rs.getString(3))), datasetId, assetId, interval, includeWarmup, limit, offset);
    }

    /** 시각화용: 워밍업을 뺀 [from, to) 구간 전체. from·to 가 null 이면 끝까지. */
    public Rows load(long datasetId, long assetId, String interval, Instant from, Instant to) {
        return load(datasetId, assetId, interval, from, to, null);
    }

    /** onProgress: 5만 행마다 지금까지 읽은 행 수를 알려준다 (진행률 · 중지 확인용, 예외를 던지면 읽기를 멈춘다) */
    public Rows load(long datasetId, long assetId, String interval, Instant from, Instant to, IntConsumer onProgress) {
        List<Long> times = new ArrayList<>();
        List<double[]> values = new ArrayList<>();
        jdbc.query("""
                SELECT open_time, feature_values FROM dataset_row
                 WHERE dataset_id = ? AND asset_id = ? AND bar_interval = ? AND is_warmup = FALSE
                   AND open_time >= ? AND open_time < ?
                 ORDER BY open_time
                """, rs -> {
            times.add(rs.getTimestamp(1).toInstant().toEpochMilli());
            values.add(JsonArrays.read(rs.getString(2)));
            if (onProgress != null && times.size() % 50_000 == 0) {
                onProgress.accept(times.size());
            }
        }, datasetId, assetId, interval,
                Timestamp.from(from == null ? Instant.EPOCH : from),
                Timestamp.from(to == null ? Instant.parse("9999-01-01T00:00:00Z") : to));
        long[] t = new long[times.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = times.get(i);
        }
        return new Rows(t, values);
    }

    /** 큰 데이터세트도 지울 수 있도록 나눠서 지운다. */
    public void deleteByDataset(long datasetId) {
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM dataset_row WHERE dataset_id = ? LIMIT 20000", datasetId);
        } while (deleted > 0);
    }

    public record RowInsert(long time, boolean warmup, String json) {
    }

    public record RowView(long time, boolean warmup, double[] values) {
    }

    /** values.get(i) 는 i 번째 행의 열 값 배열 (열 순서 = column_index) */
    public record Rows(long[] time, List<double[]> values) {
        public int size() {
            return time.length;
        }

        public double[] column(int index) {
            double[] out = new double[time.length];
            for (int i = 0; i < out.length; i++) {
                double[] row = values.get(i);
                out[i] = index < row.length ? row[index] : Double.NaN;
            }
            return out;
        }
    }
}
