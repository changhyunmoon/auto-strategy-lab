package com.mch.strategy.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.mch.strategy.collect.RawCollectService;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.dataset.DatasetColumn;
import com.mch.strategy.dataset.DatasetQueryService;
import com.mch.strategy.dataset.DatasetViews;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureDocs;
import com.mch.strategy.feature.FeatureGroup;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;

import lombok.RequiredArgsConstructor;

/**
 * 화면 컨트롤러. Thymeleaf 템플릿과 처음 필요한 값만 넘기고,
 * 나머지(표·차트·진행률)는 페이지의 jQuery 가 /api 를 호출해 채운다.
 */
@Controller
@RequiredArgsConstructor
public class PageController {

    private final AssetRepository assetRepository;
    private final MarketRepository marketRepository;
    private final RawCollectService rawCollectService;
    private final FeatureRegistry featureRegistry;
    private final DatasetQueryService datasetQueryService;

    @GetMapping("/")
    public String home() {
        return "redirect:/collect";
    }

    /** 원본 데이터 받기: 종목 · 거래소 · 기간만 골라 1분봉을 받는다 */
    @GetMapping("/collect")
    public String collect(Model model) {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        model.addAttribute("menu", "collect");
        model.addAttribute("assets", assetRepository.findByEnabledTrueOrderByIdAsc());
        model.addAttribute("exchanges", List.of(Exchange.values()));
        model.addAttribute("defaultFrom", yesterday.minusDays(6));
        model.addAttribute("defaultTo", yesterday);
        model.addAttribute("maxDate", yesterday);
        model.addAttribute("warmupDays", rawCollectService.maxWarmupDays());
        model.addAttribute("runningJobId", rawCollectService.runningJobId());
        return "collect/index";
    }

    /** 데이터세트 만들기: 받아 둔 원본으로 간격 · 지표를 계산한다. copy = 같은 설정으로 다시 만들 데이터세트 id (화면 JS 가 읽는다) */
    @GetMapping("/datasets/new")
    public String newDataset(Model model) {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        model.addAttribute("menu", "datasets");
        model.addAttribute("assets", assetRepository.findByEnabledTrueOrderByIdAsc());
        model.addAttribute("sources", List.of(Exchange.values()));
        model.addAttribute("intervals", Intervals.SUPPORTED);
        model.addAttribute("featureGroups", featureGroups());
        model.addAttribute("featureList", featureRegistry.all().stream().map(this::featureView).toList());
        model.addAttribute("featureDocs", FeatureDocs.all());
        model.addAttribute("defaultFrom", yesterday.minusDays(6));
        model.addAttribute("defaultTo", yesterday);
        model.addAttribute("maxDate", yesterday);
        return "datasets/new";
    }

    @GetMapping("/datasets")
    public String datasets(Model model) {
        model.addAttribute("menu", "datasets");
        return "datasets/list";
    }

    @GetMapping("/datasets/{id}")
    public String datasetDetail(@PathVariable long id, Model model) {
        model.addAttribute("menu", "datasets");
        DatasetViews.Detail d = datasetQueryService.detail(id);
        model.addAttribute("d", d);
        model.addAttribute("columnDocs", columnDocs(d.columns()));
        return "datasets/detail";
    }

    @GetMapping("/viz")
    public String viz(@RequestParam(required = false) Long datasetId, Model model) {
        List<DatasetViews.Summary> ready = datasetQueryService.readyList();
        model.addAttribute("menu", "viz");
        model.addAttribute("datasets", ready);
        Long selected = datasetId != null ? datasetId : ready.isEmpty() ? null : ready.get(0).id();
        if (selected != null) {
            model.addAttribute("d", datasetQueryService.detail(selected));
        }
        return "viz/index";
    }

    /** 사이드바 「변수 탐색」: 가장 최근 완료된 데이터세트로 연다 */
    @GetMapping("/features")
    public String features(Model model) {
        List<DatasetViews.Summary> ready = datasetQueryService.readyList();
        if (!ready.isEmpty()) {
            return "redirect:/datasets/" + ready.get(0).id() + "/features";
        }
        model.addAttribute("menu", "features");
        model.addAttribute("datasets", ready);
        return "datasets/features";
    }

    /** 변수 탐색: 데이터세트의 열 하나를 골라 설명 · 그래프로 본다. col = 처음 열어 둘 열 */
    @GetMapping("/datasets/{id}/features")
    public String datasetFeatures(@PathVariable long id, @RequestParam(required = false) String col, Model model) {
        DatasetViews.Detail d = datasetQueryService.detail(id);
        model.addAttribute("menu", "features");
        model.addAttribute("datasets", datasetQueryService.readyList());
        model.addAttribute("d", d);
        model.addAttribute("navGroups", columnGroups(d.columns()));
        model.addAttribute("groupLabels", groupLabels());
        model.addAttribute("docs", columnDocs(d.columns()));
        boolean valid = col != null && d.columns().stream().anyMatch(c -> c.name().equals(col));
        model.addAttribute("selected", valid ? col : d.columns().stream()
                .filter(c -> !DatasetColumn.BAR.equals(c.featureCode())).map(DatasetViews.ColumnView::name)
                .findFirst().orElse("close"));
        return "datasets/features";
    }

    @GetMapping("/instruments")
    public String instruments(Model model) {
        model.addAttribute("menu", "instruments");
        model.addAttribute("assets", assetRepository.findAll());
        List<Market> markets = marketRepository.findAllByOrderByIdAsc();
        model.addAttribute("markets", markets);
        return "instruments/index";
    }

    /** 변수 탐색 왼쪽 목록: 봉 값 → A ~ G 그룹 순서 */
    private static List<Map<String, Object>> columnGroups(List<DatasetViews.ColumnView> columns) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("-", "봉 값");
        groupLabels().forEach((code, label) -> labels.put(code, code + ". " + label));
        List<Map<String, Object>> out = new ArrayList<>();
        labels.forEach((code, label) -> {
            List<DatasetViews.ColumnView> list = columns.stream().filter(c -> c.group().equals(code)).toList();
            if (!list.isEmpty()) out.add(Map.of("code", code, "label", label, "columns", list));
        });
        List<DatasetViews.ColumnView> unknown = columns.stream().filter(c -> !labels.containsKey(c.group())).toList();
        if (!unknown.isEmpty()) out.add(Map.of("code", "?", "label", "기타", "columns", unknown));
        return out;
    }

    /** 이 데이터세트 열들의 설명 (설명이 있는 열만) */
    private static Map<String, FeatureDocs.FeatureDoc> columnDocs(List<DatasetViews.ColumnView> columns) {
        Map<String, FeatureDocs.FeatureDoc> docs = new LinkedHashMap<>();
        columns.forEach(c -> FeatureDocs.find(c.name()).ifPresent(doc -> docs.put(c.name(), doc)));
        return docs;
    }

    private static Map<String, String> groupLabels() {
        Map<String, String> out = new LinkedHashMap<>();
        for (FeatureGroup g : FeatureGroup.values()) out.put(g.getCode(), g.getLabel());
        return out;
    }

    /** 화면용 지표 목록: 그룹 → [{code, name, param, requires, columns}] */
    private Map<FeatureGroup, List<Map<String, Object>>> featureGroups() {
        Map<FeatureGroup, List<Map<String, Object>>> out = new LinkedHashMap<>();
        featureRegistry.byGroup().forEach((group, list) -> out.put(group, list.stream().map(this::featureView).toList()));
        return out;
    }

    private Map<String, Object> featureView(FeatureDefinition f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", f.code());
        m.put("name", f.name());
        m.put("param", f.paramText());
        m.put("requires", f.requirements().stream().map(Enum::name).sorted().toList());
        m.put("requiresText", f.requirements().stream().map(r -> r.getMessage()).sorted().toList());
        m.put("columns", f.outputColumns().size());
        m.put("cols", f.outputColumns());
        Map<String, Long> warmup = new LinkedHashMap<>(); // 간격별 워밍업(분) · 필요한 원본 기간 계산용
        Intervals.SUPPORTED.forEach(iv -> warmup.put(iv, f.warmupMinutes(Intervals.minutes(iv))));
        m.put("warmup", warmup);
        return m;
    }
}
