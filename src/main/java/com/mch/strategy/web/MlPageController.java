package com.mch.strategy.web;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.mch.strategy.dataset.DatasetQueryService;
import com.mch.strategy.dataset.DatasetViews;
import com.mch.strategy.feature.FeatureGroup;
import com.mch.strategy.training.ModelRunQueryService;
import com.mch.strategy.trainset.TrainingSetQueryService;

import lombok.RequiredArgsConstructor;

/** 학습 데이터 · 모델 학습 · 실험 결과 화면 */
@Controller
@RequiredArgsConstructor
public class MlPageController {

    private final DatasetQueryService datasetQueryService;
    private final TrainingSetQueryService trainingSetQueryService;
    private final ModelRunQueryService runQueryService;

    @GetMapping("/trainsets")
    public String trainingSets(Model model) {
        model.addAttribute("menu", "trainsets");
        return "trainsets/list";
    }

    /** 학습 데이터 만들기. copy = 같은 설정으로 다시 만들 학습 데이터 id */
    @GetMapping("/trainsets/new")
    public String newTrainingSet(@RequestParam(required = false) Long copy,
                                 @RequestParam(required = false) Long datasetId, Model model) {
        model.addAttribute("menu", "trainsets");
        List<Map<String, Object>> datasets = datasetQueryService.readyList().stream()
                .map(s -> datasetOption(datasetQueryService.detail(s.id()))).toList();
        model.addAttribute("datasets", datasets);
        model.addAttribute("groups", Arrays.stream(FeatureGroup.values())
                .map(g -> Map.of("code", g.getCode(), "label", g.getCode() + ". " + g.getLabel())).toList());
        model.addAttribute("labelOptions", labelOptions());
        model.addAttribute("copy", copy == null ? null : trainingSetQueryService.detail(copy).copy());
        model.addAttribute("datasetId", datasetId);
        return "trainsets/new";
    }

    @GetMapping("/trainsets/{id}")
    public String trainingSetDetail(@PathVariable long id, Model model) {
        model.addAttribute("menu", "trainsets");
        model.addAttribute("t", trainingSetQueryService.detail(id));
        return "trainsets/detail";
    }

    /** 모델 학습. trainingSetId = 미리 고를 학습 데이터, copy = 같은 설정으로 다시 실행할 실험 id */
    @GetMapping("/training")
    public String training(@RequestParam(required = false) Long trainingSetId,
                           @RequestParam(required = false) Long copy, Model model) {
        model.addAttribute("menu", "training");
        model.addAttribute("trainingSets", trainingSetQueryService.readyList());
        model.addAttribute("models", runQueryService.modelOptions());
        model.addAttribute("trainUnits", runQueryService.trainUnits());
        model.addAttribute("balances", runQueryService.balances());
        model.addAttribute("earlyStoppings", runQueryService.earlyStoppings());
        model.addAttribute("validations", runQueryService.validations());
        Map<String, Object> copied = copy == null ? null : runQueryService.detail(copy).copy();
        model.addAttribute("copy", copied);
        model.addAttribute("selectedSetId", copied != null ? copied.get("trainingSetId") : trainingSetId);
        return "training/index";
    }

    @GetMapping("/runs")
    public String runs(@RequestParam(required = false) Long trainingSetId, Model model) {
        model.addAttribute("menu", "runs");
        model.addAttribute("trainingSets", trainingSetQueryService.list());
        model.addAttribute("selectedSetId", trainingSetId);
        return "runs/list";
    }

    @GetMapping("/runs/{id}")
    public String runDetail(@PathVariable long id, Model model) {
        model.addAttribute("menu", "runs");
        model.addAttribute("r", runQueryService.detail(id));
        return "runs/detail";
    }

    /** 정답 정의 선택지 (화면 칩) */
    private static Map<String, Object> labelOptions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("horizons", com.mch.strategy.trainset.LabelSpec.HORIZONS);
        m.put("fixed", com.mch.strategy.trainset.LabelSpec.FIXED_THRESHOLDS);
        m.put("volKs", com.mch.strategy.trainset.LabelSpec.VOL_KS);
        m.put("volWindows", com.mch.strategy.trainset.LabelSpec.VOL_WINDOWS);
        m.put("margins", com.mch.strategy.trainset.LabelSpec.MARGINS);
        m.put("defaultFeePct", com.mch.strategy.training.Backtester.DEFAULT_FEE_PCT);
        m.put("defaultSlippagePct", com.mch.strategy.training.Backtester.DEFAULT_SLIPPAGE_PCT);
        return m;
    }

    /** 학습 데이터 만들기 화면이 데이터세트를 고를 때 쓰는 정보 */
    private static Map<String, Object> datasetOption(DatasetViews.Detail d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.summary().id());
        m.put("name", d.summary().name());
        m.put("intervals", d.intervals());
        m.put("assets", d.assets());
        m.put("from", d.fromDate());
        m.put("to", d.toDate());
        m.put("groups", d.columns().stream().map(DatasetViews.ColumnView::group).filter(g -> !"-".equals(g)).distinct().toList());
        m.put("featureColumns", d.featureColumnCount());
        return m;
    }
}
