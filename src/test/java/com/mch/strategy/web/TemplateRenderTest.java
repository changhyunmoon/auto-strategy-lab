package com.mch.strategy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
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

import com.mch.strategy.dataset.DatasetViews.AssetOption;
import com.mch.strategy.dataset.DatasetViews.ColumnView;
import com.mch.strategy.dataset.DatasetViews.Detail;
import com.mch.strategy.dataset.DatasetViews.GapView;
import com.mch.strategy.dataset.DatasetViews.QualityView;
import com.mch.strategy.dataset.DatasetViews.Summary;
import com.mch.strategy.feature.FeatureGroup;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRole;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;

/** DB 없이 Thymeleaf 화면을 가짜 데이터로 실제 렌더링해 본다 (문법 · 레코드 접근 · 레이아웃 확인). */
class TemplateRenderTest {

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
    void collect_page() {
        Map<String, Object> m = base("collect");
        m.put("assets", List.of(asset(1, "BTC", "비트코인"), asset(2, "ETH", "이더리움")));
        m.put("sources", List.of(Exchange.values()));
        m.put("intervals", List.of("1m", "5m", "15m"));
        Map<FeatureGroup, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put(FeatureGroup.A_RETURN, List.of(feature("log_return", 4, List.of(), List.of())));
        groups.put(FeatureGroup.E_VOLUME, List.of(feature("upbit_volume_share", 2, List.of("BOTH_EXCHANGES"), List.of("업비트·바이낸스 1분봉 모두 필요"))));
        m.put("featureGroups", groups);
        m.put("defaultFrom", LocalDate.of(2026, 10, 15));
        m.put("defaultTo", LocalDate.of(2026, 10, 21));
        m.put("maxDate", LocalDate.of(2026, 10, 21));

        String html = render("collect/index", m);
        assertThat(html).contains("데이터 가져오기", "data-code=\"BTC\"", "업비트 1분봉", "바이낸스 1분봉",
                "data-requires=\"BOTH_EXCHANGES\"", "A. 수익률·모멘텀", "data-hover-term=\"feat_log_return\"", "data-hover-term=\"grp_A\"", "cancelBtn", "/js/collect.js", "class=\"sidebar\"",
                "nav-item active");
        assertThat(html).contains("<title>데이터 가져오기 · 자동 전략 탐색</title>");
    }

    @Test
    void dataset_pages() {
        assertThat(render("datasets/list", base("datasets"))).contains("/js/datasets.js", "statusFilter");

        Map<String, Object> m = base("datasets");
        m.put("d", detail("READY"));
        String html = render("datasets/detail", m);
        assertThat(html).contains("BTC·ETH 7일", "tag-green", "25,536", "rsi_14", "빈 구간", "업비트 KRW-ETH",
                "/viz?datasetId=3", "/collect?copy=3", "previewTable");

        m.put("d", detail("COMPUTING"));
        assertThat(render("datasets/detail", m)).contains("buildingBox", "tag-blue", "data-cancel-job=\"9\"").doesNotContain("previewTable");
    }

    @Test
    void viz_page() {
        Map<String, Object> m = base("viz");
        Detail d = detail("READY");
        m.put("datasets", List.of(d.summary()));
        m.put("d", d);
        String html = render("viz/index", m);
        assertThat(html).contains("data-columns=\"open,close,rsi_14\"", "data-features=\"rsi_14\"", "echarts", "/js/viz.js");

        Map<String, Object> empty = base("viz");
        empty.put("datasets", List.of());
        assertThat(render("viz/index", empty)).contains("완료된 데이터세트가 없습니다");
    }

    @Test
    void feature_explore_page() {
        Map<String, Object> m = base("features");
        Detail d = detail("READY");
        m.put("datasets", List.of(d.summary()));
        m.put("d", d);
        m.put("navGroups", List.of(
                Map.of("code", "-", "label", "봉 값", "columns", d.columns().subList(0, 2)),
                Map.of("code", "C", "label", "C. 범위 안의 위치·강도", "columns", d.columns().subList(2, 3))));
        m.put("groupLabels", Map.of("C", "범위 안의 위치·강도"));
        m.put("docs", Map.of("rsi_14", com.mch.strategy.feature.FeatureDocs.find("rsi_14").orElseThrow()));
        m.put("selected", "rsi_14");
        String html = render("datasets/features", m);
        assertThat(html).contains("변수 탐색", "data-selected=\"rsi_14\"", "/datasets/3/features?col=rsi_14",
                "feat-nav-item active", "C. 범위 안의 위치·강도", "/js/feature-explore.js", "nav-item active",
                "\"refLines\":[0.3,0.5,0.7]"); // 설명이 JS 변수로 들어간다

        m.put("d", detail("COMPUTING"));
        assertThat(render("datasets/features", m)).contains("완료된 데이터세트만").doesNotContain("feature-explore.js");

        Map<String, Object> empty = base("features");
        empty.put("datasets", List.of());
        assertThat(render("datasets/features", empty)).contains("완료된 데이터세트가 없습니다");
    }

    @Test
    void instruments_page() {
        Map<String, Object> m = base("instruments");
        m.put("assets", List.of(asset(1, "BTC", "비트코인")));
        Market market = mock(Market.class);
        when(market.getId()).thenReturn(1L);
        when(market.getExchange()).thenReturn(Exchange.UPBIT);
        when(market.getSymbol()).thenReturn("KRW-BTC");
        when(market.getQuoteCurrency()).thenReturn("KRW");
        when(market.getRole()).thenReturn(MarketRole.EXECUTION);
        when(market.isEnabled()).thenReturn(true);
        m.put("markets", List.of(market));
        String html = render("instruments/index", m);
        assertThat(html).contains("KRW-BTC", "tag-blue", "/api/markets/1", "/api/assets/1");
    }

    private static String render(String template, Map<String, Object> vars) {
        return engine.process(template, new Context(Locale.KOREAN, vars));
    }

    private static Map<String, Object> base(String menu) {
        Map<String, Object> m = new HashMap<>();
        m.put("menu", menu);
        return m;
    }

    private static Asset asset(long id, String code, String name) {
        Asset a = mock(Asset.class);
        when(a.getId()).thenReturn(id);
        when(a.getCode()).thenReturn(code);
        when(a.getName()).thenReturn(name);
        when(a.isEnabled()).thenReturn(true);
        return a;
    }

    private static Map<String, Object> feature(String code, int columns, List<String> requires, List<String> texts) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("code", code);
        f.put("name", code);
        f.put("param", "p");
        f.put("requires", requires);
        f.put("requiresText", texts);
        f.put("columns", columns);
        return f;
    }

    private static Detail detail(String status) {
        boolean building = !status.equals("READY") && !status.equals("FAILED");
        Summary s = new Summary(3, "BTC·ETH 7일", status, building ? "지표 계산" : "완료", building,
                List.of("BTC", "ETH"), "2026-10-15 ~ 2026-10-21", List.of("1분", "15분"), 3, building ? 0 : 25536,
                "2026-10-22 14:31", 9L, building ? 62 : 100, building ? "지표 계산" : "완료", "");
        return new Detail(s, "2026-10-15 09:00 ~ 2026-10-22 08:59", "2026-10-13 18:00",
                List.of("업비트 KRW-BTC · KRW-ETH"),
                List.of(new ColumnView(0, "open", "bar", "봉 값", "-", ""), new ColumnView(1, "close", "bar", "봉 값", "-", ""),
                        new ColumnView(2, "rsi_14", "rsi", "RSI", "C", "14")),
                1,
                building ? List.of() : List.of(new QualityView("업비트 KRW-ETH", 10080, 10071, "99.91%", "빈 구간 1개 · 9분", false)),
                building ? List.of() : List.of(new GapView("업비트 KRW-ETH", "2026-10-19 05:12", "2026-10-19 05:20", 9)),
                List.of(new AssetOption(1, "BTC"), new AssetOption(2, "ETH")),
                List.of("1m", "15m"), "4분 12초",
                List.of(1L, 2L), List.of("UPBIT"), List.of("rsi"), "2026-10-15", "2026-10-21");
    }
}
