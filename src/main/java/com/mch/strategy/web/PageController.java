package com.mch.strategy.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.mch.strategy.common.Intervals;
import com.mch.strategy.dataset.DatasetQueryService;
import com.mch.strategy.dataset.DatasetViews;
import com.mch.strategy.feature.FeatureDefinition;
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
    private final FeatureRegistry featureRegistry;
    private final DatasetQueryService datasetQueryService;

    @GetMapping("/")
    public String home() {
        return "redirect:/collect";
    }

    @GetMapping("/collect")
    public String collect(Model model) {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        model.addAttribute("menu", "collect");
        model.addAttribute("assets", assetRepository.findByEnabledTrueOrderByIdAsc());
        model.addAttribute("sources", List.of(Exchange.values()));
        model.addAttribute("intervals", Intervals.SUPPORTED);
        model.addAttribute("featureGroups", featureGroups());
        model.addAttribute("defaultFrom", yesterday.minusDays(6));
        model.addAttribute("defaultTo", yesterday);
        model.addAttribute("maxDate", yesterday);
        return "collect/index";
    }

    @GetMapping("/datasets")
    public String datasets(Model model) {
        model.addAttribute("menu", "datasets");
        return "datasets/list";
    }

    @GetMapping("/datasets/{id}")
    public String datasetDetail(@PathVariable long id, Model model) {
        model.addAttribute("menu", "datasets");
        model.addAttribute("d", datasetQueryService.detail(id));
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

    @GetMapping("/instruments")
    public String instruments(Model model) {
        model.addAttribute("menu", "instruments");
        model.addAttribute("assets", assetRepository.findAll());
        List<Market> markets = marketRepository.findAllByOrderByIdAsc();
        model.addAttribute("markets", markets);
        return "instruments/index";
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
        return m;
    }
}
