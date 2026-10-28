package com.mch.strategy.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.mch.strategy.common.Json;
import com.mch.strategy.feature.FeatureService;
import com.mch.strategy.feature.FeatureSet;
import com.mch.strategy.indicator.IndicatorConfig;
import com.mch.strategy.indicator.IndicatorKind;
import com.mch.strategy.indicator.IndicatorPlanner;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.MarketCatalog;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.label.LabelConfig;
import com.mch.strategy.label.LabelOutcome;
import com.mch.strategy.label.LabelService;
import com.mch.strategy.label.LabelSet;
import com.mch.strategy.raw.RawDataset;
import com.mch.strategy.raw.RawService;
import com.mch.strategy.split.SplitCalculator;
import com.mch.strategy.split.SplitPlan;
import com.mch.strategy.split.SplitService;

import lombok.RequiredArgsConstructor;

/**
 * 화면 컨트롤러. 처음 필요한 값만 넘기고, 표 · 미리보기 · 진행률은 페이지의 jQuery 가 /api 를 불러 채운다.
 * 사이드바 순서 = V2 흐름: ① 원본 1분봉 → ② 기술 지표 → ③ 정답(H·P·L) → ④ 분할.
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final RawService rawService;
    private final FeatureService featureService;
    private final LabelService labelService;
    private final SplitService splitService;
    private final MarketCatalog marketCatalog;
    private final AssetRepository assetRepository;
    private final MarketRepository marketRepository;
    private final JobRepository jobRepository;

    // ---------------------------------------------------------------- 전체 흐름

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("menu", "home");
        model.addAttribute("raws", rawService.list());
        model.addAttribute("features", featureService.list());
        model.addAttribute("labels", labelService.list());
        model.addAttribute("splits", splitService.list());
        return "home";
    }

    // ---------------------------------------------------------------- ① 원본

    @GetMapping("/raw")
    public String rawList(Model model) {
        model.addAttribute("menu", "raw");
        List<RawDataset> raws = rawService.list();
        model.addAttribute("raws", raws);
        model.addAttribute("marketLabels", marketLabels());
        model.addAttribute("featureCounts", raws.stream().collect(Collectors.toMap(RawDataset::getId,
                r -> featureService.byRaw(r.getId()).size())));
        return "raw/list";
    }

    @GetMapping("/raw/new")
    public String rawNew(Model model) {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        model.addAttribute("menu", "raw");
        model.addAttribute("assets", assetRepository.findByEnabledTrueOrderByIdAsc());
        model.addAttribute("exchanges", List.of(Exchange.values()));
        model.addAttribute("defaultFrom", yesterday.minusDays(6));
        model.addAttribute("defaultTo", yesterday);
        model.addAttribute("maxDate", yesterday);
        return "raw/new";
    }

    @GetMapping("/raw/{id}")
    public String rawDetail(@PathVariable long id, Model model) {
        RawDataset d = rawService.get(id);
        model.addAttribute("menu", "raw");
        model.addAttribute("d", d);
        model.addAttribute("quality", RawService.quality(d));
        model.addAttribute("marketLabels", marketLabels());
        model.addAttribute("children", featureService.byRaw(id));
        model.addAttribute("job", lastJob(Job.TYPE_RAW_BUILD, id));
        return "raw/detail";
    }

    // ---------------------------------------------------------------- ② 지표

    @GetMapping("/features")
    public String featureList(Model model) {
        model.addAttribute("menu", "features");
        List<FeatureSet> list = featureService.list();
        model.addAttribute("features", list);
        model.addAttribute("rawNames", rawService.list().stream().collect(Collectors.toMap(RawDataset::getId, RawDataset::getName)));
        model.addAttribute("labelCounts", list.stream().collect(Collectors.toMap(FeatureSet::getId,
                f -> labelService.byFeature(f.getId()).size())));
        return "features/list";
    }

    /**
     * @param rawId 처음 고를 원본
     * @param copy  이 지표 데이터셋의 설정으로 시작 (같은 설정으로 새로 만들기 · 설정 바꿔 새 버전)
     */
    @GetMapping("/features/new")
    public String featureNew(@RequestParam(required = false) Long rawId, @RequestParam(required = false) Long copy, Model model) {
        model.addAttribute("menu", "features");
        List<RawDataset> raws = rawService.readyList();
        model.addAttribute("raws", raws);
        Map<Long, Object> qualities = new LinkedHashMap<>();
        raws.forEach(r -> qualities.put(r.getId(), RawService.quality(r)));
        model.addAttribute("rawQualityJson", Json.write(qualities));
        IndicatorConfig cfg = IndicatorConfig.defaults();
        String name = "";
        if (copy != null) {
            FeatureSet src = featureService.get(copy);
            cfg = FeatureService.config(src);
            rawId = rawId == null ? src.getRawDatasetId() : rawId;
            name = src.getName() + " (복사)";
        }
        model.addAttribute("configJson", Json.write(cfg));
        model.addAttribute("defaultsJson", Json.write(IndicatorConfig.defaults()));
        model.addAttribute("selectedRaw", rawId != null ? rawId : raws.isEmpty() ? null : raws.get(0).getId());
        model.addAttribute("initialName", name);
        model.addAttribute("bundlesJson", Json.write(IndicatorPlanner.BUNDLES));
        model.addAttribute("kinds", IndicatorKind.values());
        model.addAttribute("marketLabels", marketLabels());
        model.addAttribute("codeVersion", IndicatorPlanner.CODE_VERSION);
        return "features/new";
    }

    @GetMapping("/features/{id}")
    public String featureDetail(@PathVariable long id, Model model) {
        FeatureSet f = featureService.get(id);
        RawDataset raw = rawService.get(f.getRawDatasetId());
        model.addAttribute("menu", "features");
        model.addAttribute("f", f);
        model.addAttribute("raw", raw);
        model.addAttribute("columnsJson", Json.write(FeatureService.columns(f)));
        model.addAttribute("configJson", Json.write(FeatureService.config(f)));
        model.addAttribute("marketIds", raw.marketIdList());
        model.addAttribute("marketLabels", marketLabels());
        model.addAttribute("marketLabelsJson", Json.write(marketLabels()));
        model.addAttribute("kinds", IndicatorKind.values());
        model.addAttribute("children", labelService.byFeature(id));
        model.addAttribute("job", lastJob(Job.TYPE_FEATURE_BUILD, id));
        return "features/detail";
    }

    // ---------------------------------------------------------------- ③ 정답

    @GetMapping("/labels")
    public String labelList(Model model) {
        model.addAttribute("menu", "labels");
        List<LabelSet> list = labelService.list();
        model.addAttribute("labels", list);
        model.addAttribute("configs", list.stream().collect(Collectors.toMap(LabelSet::getId, LabelService::config)));
        model.addAttribute("featureNames", featureService.list().stream().collect(Collectors.toMap(FeatureSet::getId, FeatureSet::getName)));
        model.addAttribute("splitCounts", list.stream().collect(Collectors.toMap(LabelSet::getId, l -> splitService.byLabel(l.getId()).size())));
        return "labels/list";
    }

    @GetMapping("/labels/new")
    public String labelNew(@RequestParam(required = false) Long featureSetId, @RequestParam(required = false) Long copy, Model model) {
        model.addAttribute("menu", "labels");
        List<FeatureSet> features = featureService.readyList();
        model.addAttribute("features", features);
        Map<Long, Object> ranges = new LinkedHashMap<>();
        for (FeatureSet f : features) {
            RawDataset r = rawService.get(f.getRawDatasetId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rawName", r.getName());
            m.put("exchange", r.getExchange().getLabel());
            m.put("from", r.getRangeFrom());
            m.put("to", r.getRangeTo());
            m.put("rows", r.getRowCount());
            m.put("markets", r.marketIdList().stream().map(id -> marketLabels().get(id)).toList());
            m.put("quality", RawService.quality(r));
            ranges.put(f.getId(), m);
        }
        model.addAttribute("featureInfoJson", Json.write(ranges));
        LabelConfig cfg = null;
        String name = "";
        String exploreTo = null;
        if (copy != null) {
            LabelSet src = labelService.get(copy);
            cfg = LabelService.config(src);
            featureSetId = featureSetId == null ? src.getFeatureSetId() : featureSetId;
            name = src.getName() + " (복사)";
            exploreTo = src.getExploreTo().toString();
        }
        model.addAttribute("configJson", cfg == null ? "null" : Json.write(cfg));
        model.addAttribute("initialExploreTo", exploreTo);
        model.addAttribute("initialName", name);
        model.addAttribute("selectedFeature", featureSetId != null ? featureSetId : features.isEmpty() ? null : features.get(0).getId());
        model.addAttribute("outcomes", LabelOutcome.values());
        return "labels/new";
    }

    @GetMapping("/labels/{id}")
    public String labelDetail(@PathVariable long id, Model model) {
        LabelSet l = labelService.get(id);
        FeatureSet f = featureService.get(l.getFeatureSetId());
        RawDataset raw = rawService.get(f.getRawDatasetId());
        model.addAttribute("menu", "labels");
        model.addAttribute("l", l);
        model.addAttribute("f", f);
        model.addAttribute("raw", raw);
        model.addAttribute("c", LabelService.config(l));
        model.addAttribute("summaryJson", l.getSummary() == null ? "null" : l.getSummary());
        model.addAttribute("marketIds", raw.marketIdList());
        model.addAttribute("marketLabels", marketLabels());
        model.addAttribute("outcomes", LabelOutcome.values());
        model.addAttribute("children", splitService.byLabel(id));
        model.addAttribute("job", lastJob(Job.TYPE_LABEL_BUILD, id));
        return "labels/detail";
    }

    // ---------------------------------------------------------------- ④ 분할

    @GetMapping("/splits")
    public String splitList(Model model) {
        model.addAttribute("menu", "splits");
        List<SplitPlan> list = splitService.list();
        model.addAttribute("splits", list);
        model.addAttribute("configs", list.stream().collect(Collectors.toMap(SplitPlan::getId, SplitService::config)));
        model.addAttribute("labelNames", labelService.list().stream().collect(Collectors.toMap(LabelSet::getId, LabelSet::getName)));
        return "splits/list";
    }

    @GetMapping("/splits/new")
    public String splitNew(@RequestParam(required = false) Long labelSetId, @RequestParam(required = false) Long copy, Model model) {
        model.addAttribute("menu", "splits");
        List<LabelSet> labels = labelService.readyList();
        model.addAttribute("labels", labels);
        Map<Long, Object> info = new LinkedHashMap<>();
        for (LabelSet l : labels) {
            FeatureSet f = featureService.get(l.getFeatureSetId());
            RawDataset r = rawService.get(f.getRawDatasetId());
            LabelConfig c = LabelService.config(l);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("featureName", f.getName());
            m.put("featureId", f.getId());
            m.put("rawName", r.getName());
            m.put("from", r.getRangeFrom());
            m.put("to", r.getRangeTo());
            m.put("exploreTo", l.getExploreTo());
            m.put("horizon", c.horizon());
            m.put("target", c.targetPct());
            m.put("stop", c.stopPct());
            m.put("markets", r.marketIdList().stream().map(id -> marketLabels().get(id)).toList());
            info.put(l.getId(), m);
        }
        model.addAttribute("labelInfoJson", Json.write(info));
        String cfg = "null";
        String name = "";
        if (copy != null) {
            SplitPlan src = splitService.get(copy);
            cfg = src.getConfig();
            labelSetId = labelSetId == null ? src.getLabelSetId() : labelSetId;
            name = src.getName() + " (복사)";
        }
        model.addAttribute("configJson", cfg);
        model.addAttribute("initialName", name);
        model.addAttribute("selectedLabel", labelSetId != null ? labelSetId : labels.isEmpty() ? null : labels.get(0).getId());
        model.addAttribute("reasons", SplitCalculator.Reason.values());
        return "splits/new";
    }

    @GetMapping("/splits/{id}")
    public String splitDetail(@PathVariable long id, Model model) {
        SplitPlan p = splitService.get(id);
        LabelSet l = labelService.get(p.getLabelSetId());
        FeatureSet f = featureService.get(l.getFeatureSetId());
        RawDataset raw = rawService.get(f.getRawDatasetId());
        model.addAttribute("menu", "splits");
        model.addAttribute("p", p);
        model.addAttribute("l", l);
        model.addAttribute("f", f);
        model.addAttribute("raw", raw);
        model.addAttribute("lc", LabelService.config(l));
        model.addAttribute("c", SplitService.config(p));
        var summary = SplitService.publicSummary(p);
        model.addAttribute("summaryJson", summary == null ? "null" : Json.write(summary));
        model.addAttribute("testViewLog", SplitService.testViewLog(p));
        return "splits/detail";
    }

    // ---------------------------------------------------------------- 설정

    @GetMapping("/instruments")
    public String instruments(Model model) {
        model.addAttribute("menu", "instruments");
        model.addAttribute("assets", assetRepository.findAll());
        model.addAttribute("markets", marketRepository.findAllByOrderByIdAsc());
        return "instruments/index";
    }

    // ---------------------------------------------------------------- 도움

    private Map<Long, String> marketLabels() {
        return marketCatalog.all().values().stream()
                .collect(Collectors.toMap(MarketCatalog.MarketInfo::id, MarketCatalog.MarketInfo::label,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private Job lastJob(String type, long id) {
        return jobRepository.findFirstByJobTypeAndTargetIdOrderByIdDesc(type, id).orElse(null);
    }
}
