package com.mch.strategy.viz;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.common.TimeUtils;
import com.mch.strategy.dataset.Dataset;
import com.mch.strategy.dataset.DatasetColumn;
import com.mch.strategy.dataset.DatasetQueryService;
import com.mch.strategy.dataset.DatasetRowRepository;
import com.mch.strategy.dataset.DatasetRowRepository.Rows;
import com.mch.strategy.dataset.DatasetStatus;

import lombok.RequiredArgsConstructor;

/** 시각화 화면용 계산. 데이터세트 행을 읽어 차트에 맞는 모양으로 바꾼다. */
@Service
@RequiredArgsConstructor
public class VizService {

    private static final int MAX_POINTS = 5000;

    private final DatasetQueryService datasetQueryService;
    private final DatasetRowRepository rowRepository;

    /**
     * 캔들 + 선택한 열. 점이 maxPoints 보다 많으면 k 개씩 묶어 보낸다
     * (캔들은 시고저종 재집계, 거래량은 합, 지표는 평균).
     */
    public Map<String, Object> series(long datasetId, long assetId, String interval, Instant from, Instant to,
                                      List<String> columns, Integer maxPoints) {
        Context ctx = context(datasetId);
        Rows rows = rowRepository.load(datasetId, assetId, interval, from, to);
        int n = rows.size();
        int limit = maxPoints == null ? MAX_POINTS : Math.max(100, Math.min(maxPoints, 20000));
        int k = Math.max(1, (int) Math.ceil(n / (double) limit));
        int m = (n + k - 1) / k;

        double[] o = rows.column(ctx.index("open")), h = rows.column(ctx.index("high")), l = rows.column(ctx.index("low"));
        double[] c = rows.column(ctx.index("close")), v = rows.column(ctx.index("volume"));
        List<String> times = new ArrayList<>(m);
        List<double[]> candles = new ArrayList<>(m);
        List<Double> volume = new ArrayList<>(m);
        long[] bucketStart = new long[m];
        for (int b = 0; b < m; b++) {
            int s = b * k, e = Math.min(n, s + k);
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY, vol = 0;
            for (int i = s; i < e; i++) {
                hi = Math.max(hi, h[i]);
                lo = Math.min(lo, l[i]);
                vol += v[i];
            }
            bucketStart[b] = rows.time()[s];
            times.add(TimeUtils.toKstText(Instant.ofEpochMilli(rows.time()[s])));
            candles.add(new double[]{o[s], c[e - 1], lo, hi});
            volume.add(vol);
        }

        Map<String, List<Double>> series = new LinkedHashMap<>();
        for (String col : columns == null ? List.<String>of() : columns) {
            if (!ctx.has(col)) continue;
            double[] x = rows.column(ctx.index(col));
            List<Double> out = new ArrayList<>(m);
            for (int b = 0; b < m; b++) {
                int s = b * k, e = Math.min(n, s + k);
                double sum = 0;
                int cnt = 0;
                for (int i = s; i < e; i++) {
                    if (!Double.isNaN(x[i])) {
                        sum += x[i];
                        cnt++;
                    }
                }
                out.add(cnt == 0 ? null : sum / cnt);
            }
            series.put(col, out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("times", times);
        result.put("timestamps", Arrays.stream(bucketStart).boxed().toList());
        result.put("candles", candles);
        result.put("volume", volume);
        result.put("series", series);
        result.put("bucketBars", k);
        result.put("bucketText", k == 1 ? Intervals.label(interval)
                : (Intervals.minutes(interval) * k) + "분 묶음 (" + k + "봉씩)");
        result.put("totalBars", n);
        result.put("from", n == 0 ? null : rows.time()[0]);
        result.put("to", n == 0 ? null : rows.time()[n - 1] + Intervals.minutes(interval) * 60_000L);
        return result;
    }

    /** 지표 열끼리의 상관 행렬 (값이 둘 다 있는 행만 사용) */
    public Map<String, Object> correlation(long datasetId, long assetId, String interval, List<String> columns) {
        Context ctx = context(datasetId);
        List<String> cols = (columns == null || columns.isEmpty() ? ctx.featureColumns() : columns)
                .stream().filter(ctx::has).toList();
        Rows rows = rowRepository.load(datasetId, assetId, interval, null, null);
        double[][] data = new double[cols.size()][];
        for (int i = 0; i < cols.size(); i++) data[i] = rows.column(ctx.index(cols.get(i)));
        List<List<Double>> matrix = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            List<Double> line = new ArrayList<>();
            for (int j = 0; j < cols.size(); j++) {
                double r = i == j ? 1 : pearson(data[i], data[j]);
                line.add(Double.isNaN(r) ? null : Math.round(r * 100) / 100.0);
            }
            matrix.add(line);
        }
        return Map.of("columns", cols, "matrix", matrix);
    }

    private Context context(long datasetId) {
        Dataset d = datasetQueryService.get(datasetId);
        if (d.getStatus() != DatasetStatus.READY) {
            throw ApiException.badRequest("완료된 데이터세트만 볼 수 있습니다.");
        }
        return new Context(datasetQueryService.columns(datasetId));
    }

    static double pearson(double[] a, double[] b) {
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (Double.isNaN(a[i]) || Double.isNaN(b[i])) continue;
            sa += a[i]; sb += b[i];
            saa += a[i] * a[i]; sbb += b[i] * b[i]; sab += a[i] * b[i];
            n++;
        }
        if (n < 3) return Double.NaN;
        double cov = sab / n - (sa / n) * (sb / n);
        double va = saa / n - (sa / n) * (sa / n), vb = sbb / n - (sb / n) * (sb / n);
        return va > 0 && vb > 0 ? cov / Math.sqrt(va * vb) : Double.NaN;
    }

    /** 열 이름 → 위치 */
    private static final class Context {
        private final Map<String, Integer> index = new LinkedHashMap<>();
        private final List<String> features = new ArrayList<>();

        Context(List<DatasetColumn> columns) {
            for (DatasetColumn c : columns) {
                index.put(c.getColumnName(), c.getColumnIndex());
                if (!c.isBar()) features.add(c.getColumnName());
            }
        }

        boolean has(String col) {
            return index.containsKey(col);
        }

        int index(String col) {
            Integer i = index.get(col);
            if (i == null) throw ApiException.badRequest("열이 없습니다: " + col);
            return i;
        }

        List<String> featureColumns() {
            return features;
        }
    }
}
