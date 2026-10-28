package com.mch.strategy.split;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.DataFiles;
import com.mch.strategy.common.Json;
import com.mch.strategy.feature.FeatureService;
import com.mch.strategy.feature.FeatureSet;
import com.mch.strategy.instrument.MarketCatalog;
import com.mch.strategy.label.LabelOutcome;
import com.mch.strategy.label.LabelService;
import com.mch.strategy.label.LabelSet;
import com.mch.strategy.raw.RawDataset;
import com.mch.strategy.raw.RawQuality;
import com.mch.strategy.raw.RawService;

import lombok.RequiredArgsConstructor;

/**
 * 학습 · 검증 · 최종 테스트 분할: 미리보기 · 만들기 · 조회 · 최종 테스트 분포 열람(기록).
 * 계산이 몇 초면 끝나서 백그라운드 작업 없이 바로 만든다.
 */
@Service
@RequiredArgsConstructor
public class SplitService {

    private static final String[] SEGMENTS = {"TRAIN", "VAL", "TEST", "NONE"};

    private final SplitPlanRepository repository;
    private final LabelService labelService;
    private final FeatureService featureService;
    private final RawService rawService;
    private final MarketCatalog marketCatalog;
    private final DataFiles dataFiles;

    public record PreviewRequest(Long labelSetId, SplitConfig config) {
    }

    public record CreateRequest(String name, String description, Long labelSetId, SplitConfig config) {
    }

    /** 미리보기: 저장하지 않고 요약을 계산한다 (최종 테스트 정답 분포는 빼고 돌려준다) */
    public SplitSummary preview(PreviewRequest r) {
        return compute(r.labelSetId(), r.config(), false).summary.hideTest();
    }

    public SplitPlan create(CreateRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("분할 계획 이름을 입력하세요.");
        Computed c = compute(r.labelSetId(), r.config(), true);
        SplitPlan p = new SplitPlan();
        p.setName(r.name().trim());
        p.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().trim());
        p.setStatus(BuildStatus.BUILDING);
        p.setLabelSetId(r.labelSetId());
        p.setConfig(Json.write(r.config()));
        p.setCodeVersion(SplitConfig.CODE_VERSION);
        p.setCreatedAt(Instant.now());
        p = repository.save(p);
        try {
            for (Map.Entry<Long, SplitCalculator.Result> e : c.results.entrySet()) {
                DataFiles.writeBytes(dataFiles.splitDir(p.getId()).resolve(e.getKey() + ".segment"), e.getValue().segment);
                DataFiles.writeBytes(dataFiles.splitDir(p.getId()).resolve(e.getKey() + ".reason"), e.getValue().reason);
            }
            p.setSummary(Json.write(c.summary));
            p.setStatus(BuildStatus.READY);
        } catch (RuntimeException e) {
            p.setStatus(BuildStatus.FAILED);
            p.setMessage(e.getMessage());
        }
        p.setFinishedAt(Instant.now());
        return repository.save(p);
    }

    private record Computed(SplitSummary summary, Map<Long, SplitCalculator.Result> results) {
    }

    private Computed compute(Long labelSetId, SplitConfig cfg, boolean keepRows) {
        if (labelSetId == null) throw ApiException.badRequest("정답 데이터셋을 고르세요.");
        if (cfg == null) throw ApiException.badRequest("분할 설정이 없습니다.");
        LabelSet l = labelService.get(labelSetId);
        if (l.getStatus() != BuildStatus.READY) throw ApiException.badRequest("완료된 정답 데이터셋만 고를 수 있습니다.");
        FeatureSet f = featureService.get(l.getFeatureSetId());
        RawDataset raw = rawService.get(f.getRawDatasetId());
        List<String> problems = cfg.problems(raw.getRangeFrom(), raw.getRangeTo());
        if (!problems.isEmpty()) throw ApiException.badRequest(String.join(" ", problems));

        SplitCalculator.Bounds b = SplitCalculator.Bounds.of(cfg, raw.getRangeFrom(), raw.getRangeTo());
        List<SplitSummary.MarketSplit> markets = new ArrayList<>();
        Map<Long, SplitCalculator.Result> results = new LinkedHashMap<>();
        for (long marketId : raw.marketIdList()) {
            LabelService.Stored s = labelService.load(l, marketId);
            int[] nulls = featureService.rowNulls(f.getId(), marketId);
            SplitCalculator.Result res = SplitCalculator.assign(s.time(), s.status(), s.endTime(), nulls, b);
            Map<String, SplitSummary.SegView> seg = new LinkedHashMap<>();
            byte[] codes = {SplitCalculator.TRAIN, SplitCalculator.VAL, SplitCalculator.TEST, SplitCalculator.NONE};
            for (int i = 0; i < codes.length; i++) {
                SplitCalculator.SegmentStats st = res.stats.get(codes[i]);
                seg.put(SEGMENTS[i], new SplitSummary.SegView(st.rows, st.valid, st.validWithNullInputs, st.reasons, st.labels));
            }
            markets.add(new SplitSummary.MarketSplit(marketId, marketCatalog.get(marketId).label(), seg));
            if (keepRows) results.put(marketId, res);
        }
        SplitSummary.Boundaries bounds = new SplitSummary.Boundaries(Instant.ofEpochMilli(b.useFrom()), Instant.ofEpochMilli(b.useTo()),
                Instant.ofEpochMilli(b.trainFrom()), Instant.ofEpochMilli(b.trainTo()), Instant.ofEpochMilli(b.valFrom()),
                Instant.ofEpochMilli(b.valTo()), Instant.ofEpochMilli(b.testFrom()), Instant.ofEpochMilli(b.testTo()), cfg.embargoMinutes());
        List<String> warnings = warnings(markets, l, b);
        return new Computed(new SplitSummary(markets, warnings, bounds, timeline(bounds, raw, markets)), results);
    }

    private static List<String> warnings(List<SplitSummary.MarketSplit> markets, LabelSet l, SplitCalculator.Bounds b) {
        List<String> w = new ArrayList<>();
        if (b.testFrom() < l.getExploreTo().toEpochMilli()) {
            w.add("최종 테스트 시작(" + Instant.ofEpochMilli(b.testFrom()).toString().substring(0, 10) + ")이 정답 화면의 탐색 기간 끝("
                    + l.getExploreTo().toString().substring(0, 10) + ")보다 앞섭니다. 최종 테스트 기간 일부의 정답 분포를 H·P·L 을 고르며 이미 봤을 수 있습니다.");
        }
        long maxValid = markets.stream().mapToLong(m -> m.segments().get("TRAIN").valid()).max().orElse(0);
        for (SplitSummary.MarketSplit m : markets) {
            for (String s : new String[]{"TRAIN", "VAL", "TEST"}) {
                SplitSummary.SegView v = m.segments().get(s);
                String name = segName(s);
                if (v.valid() == 0) w.add(m.label() + ": " + name + " 구간에 쓸 수 있는 행이 없습니다.");
                else if (v.valid() < 1_000) w.add(m.label() + ": " + name + " 구간의 유효 행이 " + v.valid() + "개로 적습니다.");
            }
            SplitSummary.SegView train = m.segments().get("TRAIN");
            if (maxValid > 0 && train.valid() < maxValid / 2) {
                w.add(m.label() + ": 학습 유효 행이 다른 거래쌍의 절반보다 적습니다 (확보 기간 · 빈 1분 차이 확인).");
            }
            for (String s : new String[]{"TRAIN", "VAL"}) {
                SplitSummary.SegView v = m.segments().get(s);
                if (v.valid() == 0) continue;
                for (LabelOutcome o : List.of(LabelOutcome.TARGET, LabelOutcome.STOP, LabelOutcome.TIMEOUT)) {
                    long c = v.labels().getOrDefault(o.name(), 0L);
                    if (c < 30 || (double) c / v.valid() < 0.01) {
                        w.add(m.label() + ": " + segName(s) + " 구간에 「" + o.getLabel() + "」이 " + c + "건뿐입니다.");
                    }
                }
            }
            SplitSummary.SegView tr = m.segments().get("TRAIN");
            SplitSummary.SegView va = m.segments().get("VAL");
            if (tr.valid() > 0 && va.valid() > 0) {
                for (LabelOutcome o : List.of(LabelOutcome.TARGET, LabelOutcome.STOP, LabelOutcome.TIMEOUT)) {
                    double a = (double) tr.labels().getOrDefault(o.name(), 0L) / tr.valid();
                    double c = (double) va.labels().getOrDefault(o.name(), 0L) / va.valid();
                    if (Math.abs(a - c) > 0.15) {
                        w.add(String.format("%s: 「%s」 비율이 학습 %.1f%% · 검증 %.1f%% 로 크게 다릅니다 (시장 상황 변화).",
                                m.label(), o.getLabel(), a * 100, c * 100));
                    }
                }
            }
        }
        return w;
    }

    private static String segName(String s) {
        return switch (s) {
            case "TRAIN" -> "학습";
            case "VAL" -> "검증";
            case "TEST" -> "최종 테스트";
            default -> "제외";
        };
    }

    /** 사용 기간 · 세 구간 · 사이 빈 기간 · 원본 빈 1분(긴 순 상위) 을 시간순으로 */
    private static List<SplitSummary.TimelineItem> timeline(SplitSummary.Boundaries b, RawDataset raw,
                                                           List<SplitSummary.MarketSplit> markets) {
        List<SplitSummary.TimelineItem> t = new ArrayList<>();
        if (b.trainFrom().isAfter(b.useFrom())) t.add(new SplitSummary.TimelineItem(b.useFrom(), b.trainFrom(), "BETWEEN", "사용 기간 시작 ~ 학습 시작: 어느 구간에도 넣지 않음 (지표 워밍업으로만 쓰임)"));
        t.add(new SplitSummary.TimelineItem(b.trainFrom(), b.trainTo(), "TRAIN", "학습 구간" + boundaryText(markets, "TRAIN")));
        if (b.valFrom().isAfter(b.trainTo())) t.add(new SplitSummary.TimelineItem(b.trainTo(), b.valFrom(), "BETWEEN", "학습 ~ 검증 사이 빈 기간: 제외"));
        t.add(new SplitSummary.TimelineItem(b.valFrom(), b.valTo(), "VAL", "검증 구간" + boundaryText(markets, "VAL")));
        if (b.testFrom().isAfter(b.valTo())) t.add(new SplitSummary.TimelineItem(b.valTo(), b.testFrom(), "BETWEEN", "검증 ~ 최종 테스트 사이 빈 기간: 제외"));
        t.add(new SplitSummary.TimelineItem(b.testFrom(), b.testTo(), "TEST", "최종 테스트 구간" + boundaryText(markets, "TEST")));
        if (b.useTo().isAfter(b.testTo())) t.add(new SplitSummary.TimelineItem(b.testTo(), b.useTo(), "BETWEEN", "최종 테스트 끝 ~ 사용 기간 끝: 제외"));
        for (RawQuality.MarketQuality q : RawService.quality(raw)) {
            for (RawQuality.Gap g : q.gaps()) {
                if (g.to().isAfter(b.useFrom()) && g.from().isBefore(b.useTo())) {
                    t.add(new SplitSummary.TimelineItem(g.from(), g.to(), "RAW_GAP", q.assetCode() + " 원본 빈 1분 " + g.minutes() + "분"));
                }
            }
        }
        t.sort((x, y) -> x.from().compareTo(y.from()));
        return t;
    }

    private static String boundaryText(List<SplitSummary.MarketSplit> markets, String seg) {
        StringBuilder sb = new StringBuilder();
        for (SplitSummary.MarketSplit m : markets) {
            SplitSummary.SegView v = m.segments().get(seg);
            long boundary = v.reasons().getOrDefault("BOUNDARY", 0L);
            long embargo = v.reasons().getOrDefault("EMBARGO", 0L);
            if (boundary + embargo > 0) {
                sb.append(" · ").append(m.label().split(" ")[0]).append(" 경계 초과 ").append(boundary).append("행");
                if (embargo > 0) sb.append(", 경계 여유 ").append(embargo).append("행");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 조회

    public SplitPlan get(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("분할 계획이 없습니다: " + id));
    }

    public List<SplitPlan> list() {
        return repository.findAllByOrderByIdDesc();
    }

    public List<SplitPlan> byLabel(long labelSetId) {
        return repository.findByLabelSetId(labelSetId);
    }

    public static SplitConfig config(SplitPlan p) {
        return Json.read(p.getConfig(), SplitConfig.class);
    }

    /** 화면용 요약 (최종 테스트 정답 분포 제외) */
    public static SplitSummary publicSummary(SplitPlan p) {
        return p.getSummary() == null ? null : Json.read(p.getSummary(), SplitSummary.class).hideTest();
    }

    public static List<Instant> testViewLog(SplitPlan p) {
        return p.getTestViewLog() == null ? List.of() : Json.read(p.getTestViewLog(), new TypeReference<List<Instant>>() { });
    }

    /** 최종 테스트 정답 분포를 연다. 연 횟수와 시각을 기록한다 (노션: 최종 테스트 열람 이력). */
    public Map<String, Object> revealTest(long id) {
        SplitPlan p = get(id);
        if (p.getStatus() != BuildStatus.READY) throw ApiException.badRequest("완료된 분할 계획만 열 수 있습니다.");
        List<Instant> log = new ArrayList<>(testViewLog(p));
        log.add(Instant.now());
        p.setTestViews(p.getTestViews() + 1);
        p.setTestViewLog(Json.write(log));
        repository.save(p);
        SplitSummary s = Json.read(p.getSummary(), SplitSummary.class);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SplitSummary.MarketSplit m : s.markets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("marketId", m.marketId());
            row.put("label", m.label());
            row.put("valid", m.segments().get("TEST").valid());
            row.put("labels", m.segments().get("TEST").labels());
            out.add(row);
        }
        return Map.of("views", p.getTestViews(), "log", log, "markets", out);
    }

    public void delete(long id) {
        SplitPlan p = get(id);
        DataFiles.deleteTree(dataFiles.splitDir(id));
        repository.delete(p);
    }
}
