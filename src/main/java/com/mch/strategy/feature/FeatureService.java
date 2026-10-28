package com.mch.strategy.feature;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.collect.CandleSeries;
import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.DataFiles;
import com.mch.strategy.common.Json;
import com.mch.strategy.indicator.IndicatorConfig;
import com.mch.strategy.indicator.IndicatorKind;
import com.mch.strategy.indicator.IndicatorPlan;
import com.mch.strategy.indicator.IndicatorPlanner;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.raw.RawDataset;
import com.mch.strategy.raw.RawQuality;
import com.mch.strategy.raw.RawService;

import lombok.RequiredArgsConstructor;

/** 기술 지표: 미리보기(설정 → 열 목록) · 만들기 · 조회 · 행 보기 · 내보내기 · 설정 저장 */
@Service
@RequiredArgsConstructor
public class FeatureService {

    private final FeatureSetRepository repository;
    private final FeaturePresetRepository presetRepository;
    private final RawService rawService;
    private final JobRepository jobRepository;
    private final FeatureBuilder builder;
    private final DataFiles dataFiles;
    private final CandleRepository candleRepository;

    // ------------------------------------------------------------------ 미리보기

    /**
     * 생성 미리보기.
     *
     * @param history 거래쌍별 과거 이력 검사: 가장 긴 끊김 없는 구간이 최대 워밍업보다 짧으면 값이 거의 안 나온다
     */
    public record PlanView(boolean ok, int plannedCount, int columnCount, int maxWarmup,
                           List<IndicatorPlan.Message> messages, List<IndicatorPlan.BundleCount> bundles,
                           Map<String, Integer> byKind, List<ColumnView> columns, List<IndicatorPlan.Duplicate> duplicates,
                           List<HistoryCheck> history) {
    }

    public record HistoryCheck(long marketId, String label, long rows, int segments, long longestSegment, boolean enough) {
    }

    public PlanView plan(IndicatorConfig config, Long rawId) {
        IndicatorPlan p = IndicatorPlanner.plan(config);
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (IndicatorKind k : IndicatorKind.values()) {
            byKind.put(k.name(), p.countFor(k));
        }
        List<ColumnView> cols = new ArrayList<>();
        for (int i = 0; i < p.columns().size(); i++) {
            cols.add(ColumnView.of(i, p.columns().get(i)));
        }
        List<HistoryCheck> history = new ArrayList<>();
        if (rawId != null) {
            RawDataset raw = rawService.get(rawId);
            for (RawQuality.MarketQuality q : RawService.quality(raw)) {
                history.add(new HistoryCheck(q.marketId(), q.assetCode() + " " + q.symbol(), q.rows(), q.segments(),
                        q.longestSegment(), q.longestSegment() > p.maxWarmup()));
            }
        }
        return new PlanView(p.ok(), p.plannedCount(), p.columns().size(), p.maxWarmup(), p.messages(), p.bundles(),
                byKind, cols, p.duplicates(), history);
    }

    // ------------------------------------------------------------------ 만들기

    public record CreateRequest(String name, Long rawDatasetId, IndicatorConfig config) {
    }

    public record Created(long id, long jobId) {
    }

    public Created create(CreateRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("지표 데이터셋 이름을 입력하세요.");
        if (r.rawDatasetId() == null) throw ApiException.badRequest("원본 데이터를 고르세요.");
        if (r.config() == null) throw ApiException.badRequest("지표 설정이 없습니다.");
        RawDataset raw = rawService.get(r.rawDatasetId());
        if (raw.getStatus() != BuildStatus.READY) throw ApiException.badRequest("완료된 원본 데이터만 고를 수 있습니다.");
        List<String> blockers = RawService.quality(raw).stream().flatMap(q -> q.blockers().stream().map(b -> q.assetCode() + ": " + b)).toList();
        if (!blockers.isEmpty()) throw ApiException.badRequest("원본 입력 검사에서 생성을 막는 문제가 있습니다: " + String.join(" / ", blockers));
        IndicatorConfig cfg = r.config().normalized();
        IndicatorPlan p = IndicatorPlanner.plan(cfg);
        if (!p.ok()) {
            String err = p.messages().stream().filter(m -> m.level().equals("ERROR")).map(IndicatorPlan.Message::text).findFirst()
                    .orElse("만들 열이 없습니다.");
            throw ApiException.badRequest(err);
        }
        FeatureSet f = new FeatureSet();
        f.setName(r.name().trim());
        f.setStatus(BuildStatus.PENDING);
        f.setRawDatasetId(raw.getId());
        f.setConfig(Json.write(cfg));
        f.setCodeVersion(IndicatorPlanner.CODE_VERSION);
        f.setPlannedCount(p.plannedCount());
        f.setColumnCount(p.columns().size());
        f.setCreatedAt(Instant.now());
        f = repository.save(f);
        Job job = jobRepository.save(Job.forTarget(Job.TYPE_FEATURE_BUILD, f.getId()));
        builder.run(f.getId(), job.getId());
        return new Created(f.getId(), job.getId());
    }

    // ------------------------------------------------------------------ 조회

    public FeatureSet get(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("지표 데이터셋이 없습니다: " + id));
    }

    public List<FeatureSet> list() {
        return repository.findAllByOrderByIdDesc();
    }

    public List<FeatureSet> readyList() {
        return repository.findByStatusOrderByIdDesc(BuildStatus.READY);
    }

    public List<FeatureSet> byRaw(long rawId) {
        return repository.findByRawDatasetId(rawId);
    }

    public static IndicatorConfig config(FeatureSet f) {
        return Json.read(f.getConfig(), IndicatorConfig.class);
    }

    public static List<ColumnView> columns(FeatureSet f) {
        if (f.getColumnsJson() == null) {
            // 아직 만드는 중: 설정에서 열 정의만 다시 펼친다
            IndicatorPlan p = IndicatorPlanner.plan(config(f));
            List<ColumnView> out = new ArrayList<>();
            for (int i = 0; i < p.columns().size(); i++) out.add(ColumnView.of(i, p.columns().get(i)));
            return out;
        }
        return Json.read(f.getColumnsJson(), new TypeReference<List<ColumnView>>() { });
    }

    public void delete(long id) {
        FeatureSet f = get(id);
        if (f.getStatus() == BuildStatus.BUILDING || f.getStatus() == BuildStatus.PENDING) {
            throw ApiException.badRequest("만드는 중에는 지울 수 없습니다. 먼저 중지하세요.");
        }
        DataFiles.deleteTree(dataFiles.featureDir(id));
        repository.delete(f);
    }

    // ------------------------------------------------------------------ 행 보기 · 내보내기

    /** 행 시각 (판단 봉 시작, UTC 밀리초) */
    public long[] times(long id, long marketId) {
        Path p = dataFiles.featureDir(id).resolve(marketId + ".time");
        if (!Files.exists(p)) throw ApiException.notFound("지표 파일이 없습니다. 데이터 폴더를 지웠거나 만드는 중입니다.");
        return DataFiles.readLongs(p);
    }

    public int[] rowNulls(long id, long marketId) {
        return DataFiles.readInts(dataFiles.featureDir(id).resolve(marketId + ".nulls"));
    }

    /** 열 하나 전체 (분할 · 분석용) */
    public float[] column(long id, long marketId, int rows, int col) {
        return DataFiles.readFloatsAt(dataFiles.featureDir(id).resolve(marketId + ".cols"), rows, col, 0, rows);
    }

    public record RowsView(long total, int offset, List<String> columns, List<Map<String, Object>> rows) {
    }

    /**
     * 행 미리보기: [offset, offset + limit) 의 원본 OHLCV + 고른 지표 열.
     *
     * @param at 이 시각(UTC ISO)부터 보기. 주어지면 offset 대신 쓴다
     */
    public RowsView rows(long id, long marketId, int offset, int limit, List<String> colNames, String kind, String at) {
        FeatureSet f = get(id);
        if (f.getStatus() != BuildStatus.READY) throw ApiException.badRequest("완료된 지표 데이터셋만 볼 수 있습니다.");
        long[] time = times(id, marketId);
        int n = time.length;
        if (at != null && !at.isBlank()) {
            long t = Instant.parse(at).toEpochMilli();
            int idx = java.util.Arrays.binarySearch(time, t);
            offset = idx >= 0 ? idx : -idx - 1;
        }
        offset = Math.max(0, Math.min(offset, Math.max(0, n - 1)));
        limit = Math.max(1, Math.min(limit, 200));
        int count = Math.min(limit, n - offset);
        List<ColumnView> all = columns(f);
        List<ColumnView> chosen;
        if (kind != null && !kind.isBlank()) {
            chosen = all.stream().filter(c -> c.kind().name().equals(kind)).limit(400).toList();
        } else if (colNames == null || colNames.isEmpty()) {
            chosen = all.subList(0, Math.min(12, all.size()));
        } else {
            chosen = all.stream().filter(c -> colNames.contains(c.name())).toList();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (count > 0) {
            CandleSeries s = candleRepository.load(marketId, Instant.ofEpochMilli(time[offset]),
                    Instant.ofEpochMilli(time[offset + count - 1] + 60_000L));
            Path file = dataFiles.featureDir(id).resolve(marketId + ".cols");
            float[][] vals = new float[chosen.size()][];
            for (int c = 0; c < chosen.size(); c++) {
                vals[c] = DataFiles.readFloatsAt(file, n, chosen.get(c).index(), offset, count);
            }
            for (int i = 0; i < count; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", Instant.ofEpochMilli(time[offset + i]).toString());
                int si = i < s.size() && s.time()[i] == time[offset + i] ? i : java.util.Arrays.binarySearch(s.time(), time[offset + i]);
                row.put("open", si >= 0 ? s.open()[si] : null);
                row.put("high", si >= 0 ? s.high()[si] : null);
                row.put("low", si >= 0 ? s.low()[si] : null);
                row.put("close", si >= 0 ? s.close()[si] : null);
                row.put("volume", si >= 0 ? s.volume()[si] : null);
                for (int c = 0; c < chosen.size(); c++) {
                    float v = vals[c][i];
                    row.put(chosen.get(c).name(), Float.isNaN(v) ? null : v);
                }
                out.add(row);
            }
        }
        return new RowsView(n, offset, chosen.stream().map(ColumnView::name).toList(), out);
    }

    /** CSV 내보내기: 원본 열(시각 · OHLCV)과 지표 열을 구분해 머리줄에 [raw] · [ind] 를 붙인다 */
    public void exportCsv(long id, long marketId, Writer w) throws IOException {
        FeatureSet f = get(id);
        if (f.getStatus() != BuildStatus.READY) throw ApiException.badRequest("완료된 지표 데이터셋만 내보낼 수 있습니다.");
        RawDataset raw = rawService.get(f.getRawDatasetId());
        long[] time = times(id, marketId);
        int n = time.length;
        List<ColumnView> cols = columns(f);
        CandleSeries s = candleRepository.load(marketId, raw.getRangeFrom(), raw.getRangeTo());
        w.write("time_utc,open,high,low,close,volume");
        for (ColumnView c : cols) w.write("," + c.name());
        w.write("\n");
        Path file = dataFiles.featureDir(id).resolve(marketId + ".cols");
        int chunk = 5_000;
        {
            for (int start = 0; start < n; start += chunk) {
                int count = Math.min(chunk, n - start);
                float[][] vals = new float[cols.size()][];
                for (int c = 0; c < cols.size(); c++) {
                    vals[c] = DataFiles.readFloatsAt(file, n, c, start, count);
                }
                StringBuilder sb = new StringBuilder(count * (cols.size() * 10 + 80));
                for (int i = 0; i < count; i++) {
                    int r = start + i;
                    sb.append(Instant.ofEpochMilli(time[r])).append(',').append(s.open()[r]).append(',').append(s.high()[r])
                            .append(',').append(s.low()[r]).append(',').append(s.close()[r]).append(',').append(s.volume()[r]);
                    for (int c = 0; c < cols.size(); c++) {
                        sb.append(',');
                        if (!Float.isNaN(vals[c][i])) sb.append(vals[c][i]);
                    }
                    sb.append('\n');
                }
                w.write(sb.toString());
            }
        }
        w.flush();
    }

    // ------------------------------------------------------------------ 설정 저장

    public FeaturePreset savePreset(String name, IndicatorConfig config) {
        if (name == null || name.isBlank()) throw ApiException.badRequest("설정 이름을 입력하세요.");
        FeaturePreset p = new FeaturePreset();
        p.setName(name.trim());
        p.setConfig(Json.write(config.normalized()));
        p.setCreatedAt(Instant.now());
        return presetRepository.save(p);
    }

    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FeaturePreset p : presetRepository.findAllByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("createdAt", p.getCreatedAt());
            m.put("config", Json.read(p.getConfig(), IndicatorConfig.class));
            out.add(m);
        }
        return out;
    }

    public void deletePreset(long id) {
        presetRepository.deleteById(id);
    }
}
