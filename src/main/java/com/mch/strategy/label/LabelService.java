package com.mch.strategy.label;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.collect.CandleSeries;
import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.DataFiles;
import com.mch.strategy.common.Json;
import com.mch.strategy.feature.FeatureService;
import com.mch.strategy.feature.FeatureSet;
import com.mch.strategy.instrument.MarketCatalog;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.raw.RawDataset;
import com.mch.strategy.raw.RawService;

import lombok.RequiredArgsConstructor;

/** H·P·L 정답: 미리보기 · 만들기 · 조회 · 행 보기 */
@Service
@RequiredArgsConstructor
public class LabelService {

    private final LabelSetRepository repository;
    private final FeatureService featureService;
    private final RawService rawService;
    private final CandleRepository candleRepository;
    private final MarketCatalog marketCatalog;
    private final JobRepository jobRepository;
    private final LabelBuilder builder;
    private final DataFiles dataFiles;

    public record PreviewRequest(Long featureSetId, LabelConfig config, Instant exploreTo) {
    }

    public record CreateRequest(String name, String description, Long featureSetId, LabelConfig config, Instant exploreTo) {
    }

    public record Created(long id, long jobId) {
    }

    /** 실행 전 미리보기: 저장하지 않고 탐색 기간의 정답 분포 · 제외 건수 · 경고를 계산한다 */
    public LabelSummary preview(PreviewRequest r) {
        FeatureSet f = readyFeature(r.featureSetId());
        RawDataset raw = rawService.get(f.getRawDatasetId());
        check(r.config(), r.exploreTo(), raw);
        List<LabelSummary.MarketSummary> markets = new ArrayList<>();
        for (long marketId : raw.marketIdList()) {
            CandleSeries s = candleRepository.load(marketId, raw.getRangeFrom(), raw.getRangeTo());
            LabelCalculator.Result res = LabelCalculator.compute(s.time(), s.open(), s.high(), s.low(), s.close(), r.config());
            markets.add(LabelSummary.summarize(marketId, marketCatalog.get(marketId).label(), s.time(), res,
                    raw.getRangeFrom().toEpochMilli(), r.exploreTo().toEpochMilli()));
        }
        return new LabelSummary(markets, LabelSummary.warnings(markets));
    }

    public Created create(CreateRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("정답 이름을 입력하세요.");
        FeatureSet f = readyFeature(r.featureSetId());
        RawDataset raw = rawService.get(f.getRawDatasetId());
        check(r.config(), r.exploreTo(), raw);
        LabelSet l = new LabelSet();
        l.setName(r.name().trim());
        l.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        l.setStatus(BuildStatus.PENDING);
        l.setFeatureSetId(f.getId());
        l.setConfig(Json.write(r.config()));
        l.setExploreTo(r.exploreTo());
        l.setCodeVersion(LabelConfig.CODE_VERSION);
        l.setCreatedAt(Instant.now());
        l = repository.save(l);
        Job job = jobRepository.save(Job.forTarget(Job.TYPE_LABEL_BUILD, l.getId()));
        builder.run(l.getId(), job.getId());
        return new Created(l.getId(), job.getId());
    }

    private FeatureSet readyFeature(Long id) {
        if (id == null) throw ApiException.badRequest("지표 데이터셋을 고르세요.");
        FeatureSet f = featureService.get(id);
        if (f.getStatus() != BuildStatus.READY) throw ApiException.badRequest("완료된 지표 데이터셋만 고를 수 있습니다.");
        return f;
    }

    private static void check(LabelConfig c, Instant exploreTo, RawDataset raw) {
        if (c == null) throw ApiException.badRequest("정답 설정이 없습니다.");
        List<String> p = c.problems();
        if (!p.isEmpty()) throw ApiException.badRequest(String.join(" ", p));
        if (exploreTo == null || !exploreTo.isAfter(raw.getRangeFrom()) || exploreTo.isAfter(raw.getRangeTo())) {
            throw ApiException.badRequest("탐색 기간 끝(이후 정답 분포 숨김)은 원본 데이터 기간 안의 날짜여야 합니다.");
        }
    }

    public LabelSet get(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("정답 데이터셋이 없습니다: " + id));
    }

    public List<LabelSet> list() {
        return repository.findAllByOrderByIdDesc();
    }

    public List<LabelSet> readyList() {
        return repository.findByStatusOrderByIdDesc(BuildStatus.READY);
    }

    public List<LabelSet> byFeature(long featureSetId) {
        return repository.findByFeatureSetId(featureSetId);
    }

    public static LabelConfig config(LabelSet l) {
        return Json.read(l.getConfig(), LabelConfig.class);
    }

    public static LabelSummary summary(LabelSet l) {
        return l.getSummary() == null ? null : Json.read(l.getSummary(), LabelSummary.class);
    }

    public void delete(long id) {
        LabelSet l = get(id);
        if (l.getStatus() == BuildStatus.BUILDING || l.getStatus() == BuildStatus.PENDING) {
            throw ApiException.badRequest("만드는 중에는 지울 수 없습니다. 먼저 중지하세요.");
        }
        DataFiles.deleteTree(dataFiles.labelDir(id));
        repository.delete(l);
    }

    // ------------------------------------------------------------------ 파일 읽기 (분할 단계에서 씀)

    public record Stored(long[] time, byte[] status, long[] endTime) {
    }

    public Stored load(LabelSet l, long marketId) {
        Path dir = dataFiles.labelDir(l.getId());
        if (!Files.exists(dir.resolve(marketId + ".status"))) {
            throw ApiException.notFound("정답 파일이 없습니다. 데이터 폴더를 지웠는지 확인하세요.");
        }
        long[] time = featureService.times(l.getFeatureSetId(), marketId);
        return new Stored(time, DataFiles.readBytes(dir.resolve(marketId + ".status")), DataFiles.readLongs(dir.resolve(marketId + ".end")));
    }

    /** 행 보기 (탐색 기간만): 판단 시각 · 종가 · 진입가 · 목표가 · 손실가 · 상태 · 판정 종료 시각 · 경과 분 · 만료 수익률 */
    public Map<String, Object> rows(long id, long marketId, int offset, int limit, String statusFilter) {
        LabelSet l = get(id);
        if (l.getStatus() != BuildStatus.READY) throw ApiException.badRequest("완료된 정답만 볼 수 있습니다.");
        LabelConfig c = config(l);
        Path dir = dataFiles.labelDir(id);
        long[] time = featureService.times(l.getFeatureSetId(), marketId);
        byte[] status = DataFiles.readBytes(dir.resolve(marketId + ".status"));
        long[] end = DataFiles.readLongs(dir.resolve(marketId + ".end"));
        double[] entry = DataFiles.readDoubles(dir.resolve(marketId + ".entry"));
        int[] bars = DataFiles.readInts(dir.resolve(marketId + ".bars"));
        double[] ret = DataFiles.readDoubles(dir.resolve(marketId + ".ret"));
        long limitMs = l.getExploreTo().toEpochMilli();
        byte filter = statusFilter == null || statusFilter.isBlank() ? 0 : LabelOutcome.valueOf(statusFilter).code();

        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < time.length && time[i] < limitMs; i++) {
            if (filter == 0 || status[i] == filter) idx.add(i);
        }
        int total = idx.size();
        offset = Math.max(0, Math.min(offset, Math.max(0, total - 1)));
        int count = Math.min(Math.max(1, Math.min(limit, 200)), total - offset);
        List<Map<String, Object>> out = new ArrayList<>();
        double tp = c.grossTargetPct() / 100;
        double sl = c.grossStopPct() / 100;
        for (int k = 0; k < count; k++) {
            int i = idx.get(offset + k);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", Instant.ofEpochMilli(time[i]).toString());
            LabelOutcome o = LabelOutcome.of(status[i]);
            row.put("status", o.name());
            double e = entry[i];
            boolean has = !Double.isNaN(e);
            row.put("entry", has ? e : null);
            row.put("target", has ? (c.isLong() ? e * (1 + tp) : e * (1 - tp)) : null);
            row.put("stop", has ? (c.isLong() ? e * (1 - sl) : e * (1 + sl)) : null);
            row.put("endTime", end[i] == 0 ? null : Instant.ofEpochMilli(end[i]).toString());
            row.put("bars", end[i] == 0 ? null : bars[i]);
            row.put("timeoutReturn", Double.isNaN(ret[i]) ? null : ret[i]);
            out.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("offset", offset);
        m.put("rows", out);
        return m;
    }
}
