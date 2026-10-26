package com.mch.strategy.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.mch.strategy.training.ModelType;
import com.mch.strategy.training.ModelRunViews;
import com.mch.strategy.training.RunOptions;
import com.mch.strategy.trainset.TrainingSetViews;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;

/** 학습 데이터 · 모델 학습 · 실험 결과 화면을 가짜 데이터로 실제 렌더링해 본다. */
class MlTemplateRenderTest {

    static SpringTemplateEngine engine;

    @BeforeAll
    static void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new LayoutDialect());
        engine.setLinkBuilder(new StandardLinkBuilder() {
            @Override
            protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) {
                return "";
            }
        });
    }

    @Test
    void trainset_pages() {
        String list = render("trainsets/list", base("trainsets"));
        assertThat(list).contains("/js/trainsets.js", "setTable", "nav-item active", "학습 데이터");

        Map<String, Object> m = base("trainsets");
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("id", 3);
        ds.put("name", "BTC·ETH 7일");
        ds.put("intervals", List.of("1m", "15m"));
        ds.put("assets", List.of(Map.of("id", 1, "code", "BTC")));
        ds.put("from", "2026-10-15");
        ds.put("to", "2026-10-21");
        ds.put("groups", List.of("A", "C"));
        ds.put("featureColumns", 20);
        m.put("datasets", List.of(ds));
        m.put("groups", List.of(Map.of("code", "A", "label", "A. 수익률·모멘텀")));
        Map<String, Object> labelOptions = new LinkedHashMap<>();
        labelOptions.put("horizons", List.of(1, 5, 10));
        labelOptions.put("fixed", List.of(0.1, 0.3, 0.5));
        labelOptions.put("volKs", List.of(0.5, 1.0));
        labelOptions.put("volWindows", List.of(20, 60));
        labelOptions.put("margins", List.of(0.0, 0.1));
        labelOptions.put("defaultFeePct", 0.05);
        labelOptions.put("defaultSlippagePct", 0.02);
        m.put("labelOptions", labelOptions);
        m.put("copy", null);
        m.put("datasetId", null);
        String form = render("trainsets/new", m);
        assertThat(form).contains("datasetSel", "BTC·ETH 7일", "±0.3%", "/js/trainset-new.js", "var DATASETS =", "\"featureColumns\":20",
                "TRIPLE_BARRIER", "data-mode=\"VOLATILITY\"", "labelPreview", "data-term=\"horizon\"", "var LABEL_OPTS =", "/js/glossary.js");

        Map<String, Object> d = base("trainsets");
        d.put("t", trainSetDetail("READY"));
        String detail = render("trainsets/detail", d);
        assertThat(detail).contains("±0.3%", "/training?trainingSetId=5", "distChart", "var STATS =", "data-train=\"2026-01-01 ~ 2026-06-30\"", "rsi_14");

        d.put("t", trainSetDetail("BUILDING"));
        assertThat(render("trainsets/detail", d)).contains("buildingBox").doesNotContain("distChart");
    }

    @Test
    void training_page() {
        Map<String, Object> m = base("training");
        m.put("trainingSets", List.of(trainSetDetail("READY").summary()));
        m.put("models", List.of(new ModelRunViews.ModelOption("GRADIENT_BOOSTING", "트리", "그래디언트 부스팅", "설명", true, false, true, false,
                ModelType.GRADIENT_BOOSTING.getParams())));
        m.put("trainUnits", List.of(new ModelRunViews.Choice(RunOptions.TrainUnit.PER_ASSET.name(), "자산별 모델", "설명")));
        m.put("balances", List.of(new ModelRunViews.Choice("NONE", "미적용", "설명")));
        m.put("earlyStoppings", List.of(new ModelRunViews.Choice("TRAIN_TAIL", "학습 구간 끝 10%", "설명")));
        m.put("validations", List.of(new ModelRunViews.Choice("WALK_FORWARD_EXPANDING", "확장형 워크포워드", "설명")));
        m.put("copy", null);
        m.put("selectedSetId", 5L);
        String html = render("training/index", m);
        assertThat(html).contains("setSel", "data-horizon=\"5\"", "var MODELS =", "\"shrinkage\"", "/js/training.js", "학습 시작",
                "WALK_FORWARD_EXPANDING", "wfTimeline", "data-term=\"purge\"");
    }

    @Test
    void run_pages() {
        Map<String, Object> m = base("runs");
        m.put("trainingSets", List.of(trainSetDetail("READY").summary()));
        m.put("selectedSetId", 5L);
        assertThat(render("runs/list", m)).contains("setFilter", "compareChart", "/js/runs.js", "selected");

        Map<String, Object> d = base("runs");
        d.put("r", runDetail("READY"));
        String html = render("runs/detail", d);
        assertThat(html).contains("그래디언트 부스팅", "학습률 0.05", "최종 테스트 실행", "/js/run-result.js",
                "/api/runs/9/predictions?id=9&amp;split=val".replace("?id=9&amp;", "?"), "소요 12.5초",
                "💰 거래 성과", "/js/run-trade.js", "확장형 워크포워드 · 폴드 4", "data-horizon=\"5\"", "valExtra");

        d.put("r", runDetail("RUNNING"));
        assertThat(render("runs/detail", d)).contains("trainProgress").doesNotContain("testBtn");
    }

    private static TrainingSetViews.Detail trainSetDetail(String status) {
        boolean building = status.equals("BUILDING");
        TrainingSetViews.Summary s = new TrainingSetViews.Summary(5, "BTC·ETH 1분 ±0.3%", status, building ? "만드는 중" : "완료", building,
                3, "BTC·ETH 7일", List.of("BTC", "ETH"), "1분", List.of("15분"), List.of("A", "C"), "0.3",
                "5봉(1분) 뒤 종가 수익률 · 기준 ±0.3%", 5,
                "2026-01-01 ~ 2026-06-30", "2026-07-01 ~ 2026-09-30", "2026-10-01 ~ 2026-10-21",
                building ? 0 : 123456, building ? 0 : 40, "2026-10-26 15:00", 7L, building ? 40 : 100, "BTC 정답 · 구간 계산", "", 2);
        return new TrainingSetViews.Detail(s, false, List.of("A. 수익률·모멘텀"), building ? List.of() : List.of("rsi_14", "rsi_14@15m"),
                building ? Map.of() : Map.of("splits", Map.of()), Map.of());
    }

    private static ModelRunViews.Detail runDetail(String status) {
        boolean busy = !status.equals("READY");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("shrinkage", 0.05);
        ModelRunViews.Summary s = new ModelRunViews.Summary(9, "실험 · 그래디언트 부스팅", 5, "BTC·ETH 1분 ±0.3%", "GRADIENT_BOOSTING",
                "그래디언트 부스팅", "트리", status, busy ? "학습 중" : "완료", busy, "자산별 모델", "미적용", params, 42, 200000,
                0.5, 0.9, 0.4, 0.38, 0.3, 0.32, "확장형 워크포워드", 4, 0.37, 0.02, 0.35, 1.25, 3.5, 40,
                "NONE", null, null, null, null, null, null, null, 0, "", busy ? null : 12.5, "2026-10-26 15:00",
                11L, 50, "BTC 학습 · 트리 10 / 300", null, 0, "", "");
        return new ModelRunViews.Detail(s, "설명", true, List.of(new ModelRunViews.ParamView("학습률", "0.05")),
                "자산별", "미적용", "학습 구간 끝 10%", "확장형 워크포워드 · 폴드 4 · 첫 학습 50%", "설명",
                "5봉(1분) 뒤 종가 수익률 · 기준 ±0.3%", 5, true, "", "1분", "0.3", List.of("BTC", "ETH"),
                "2026-01-01 ~ 2026-06-30", "2026-07-01 ~ 2026-09-30", "2026-10-01 ~ 2026-10-21", Map.of());
    }

    private static String render(String template, Map<String, Object> vars) {
        return engine.process(template, new Context(Locale.KOREAN, vars));
    }

    private static Map<String, Object> base(String menu) {
        Map<String, Object> m = new HashMap<>();
        m.put("menu", menu);
        return m;
    }
}
