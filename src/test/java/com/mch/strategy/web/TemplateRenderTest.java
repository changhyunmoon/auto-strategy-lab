package com.mch.strategy.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

import com.mch.strategy.collect.CandleSeries;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.Json;
import com.mch.strategy.feature.FeatureSet;
import com.mch.strategy.indicator.IndicatorConfig;
import com.mch.strategy.indicator.IndicatorKind;
import com.mch.strategy.indicator.IndicatorPlanner;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.label.LabelCalculator;
import com.mch.strategy.label.LabelConfig;
import com.mch.strategy.label.LabelOutcome;
import com.mch.strategy.label.LabelSet;
import com.mch.strategy.label.LabelSummary;
import com.mch.strategy.raw.RawDataset;
import com.mch.strategy.raw.RawQuality;
import com.mch.strategy.split.SplitCalculator;
import com.mch.strategy.split.SplitConfig;
import com.mch.strategy.split.SplitPlan;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;

/** DB 없이 V2 화면을 가짜 데이터로 실제 렌더링해 본다 (문법 · 레코드 접근 · 레이아웃 · 용어 표시 확인). */
class TemplateRenderTest {

    static SpringTemplateEngine engine;
    static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant TO = Instant.parse("2026-01-11T00:00:00Z");

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

    static String render(String view, Map<String, Object> model) {
        Context ctx = new Context(Locale.KOREA);
        ctx.setVariables(model);
        return engine.process(view, ctx);
    }

    static Map<String, Object> base(String menu) {
        Map<String, Object> m = new HashMap<>();
        m.put("menu", menu);
        return m;
    }

    static RawDataset raw() {
        RawDataset d = new RawDataset();
        d.setId(1L);
        d.setName("업비트 BTC·ETH 10일");
        d.setStatus(BuildStatus.READY);
        d.setExchange(Exchange.UPBIT);
        d.setMarketIdList(List.of(1L, 2L));
        d.setRangeFrom(FROM);
        d.setRangeTo(TO);
        d.setRowCount(28800);
        d.setCreatedAt(TO);
        long[] t = {FROM.toEpochMilli(), FROM.toEpochMilli() + 60_000, FROM.toEpochMilli() + 180_000};
        double[] p = {100, 101, 102};
        CandleSeries s = new CandleSeries(t, p, p, p, p, p, p, p, p);
        RawQuality.MarketQuality q = RawQuality.check(1, "BTC", "KRW-BTC", "KRW", s, FROM, TO, Instant.now());
        d.setQuality(Json.write(List.of(q)));
        return d;
    }

    static FeatureSet feature() {
        FeatureSet f = new FeatureSet();
        f.setId(2L);
        f.setName("기본 지표");
        f.setStatus(BuildStatus.READY);
        f.setRawDatasetId(1L);
        f.setConfig(Json.write(IndicatorConfig.defaults()));
        f.setCodeVersion(IndicatorPlanner.CODE_VERSION);
        f.setColumnCount(10);
        f.setPlannedCount(12);
        f.setRowCount(28800);
        f.setCreatedAt(TO);
        return f;
    }

    static LabelConfig labelConfig() {
        return new LabelConfig(30, 0.5, 0.3, "LONG", "NEXT_OPEN", "AMBIGUOUS", true, 0.05, 0.02);
    }

    static LabelSet label() {
        LabelSet l = new LabelSet();
        l.setId(3L);
        l.setName("H30 P0.5 L0.3");
        l.setStatus(BuildStatus.READY);
        l.setFeatureSetId(2L);
        l.setConfig(Json.write(labelConfig()));
        l.setExploreTo(Instant.parse("2026-01-09T00:00:00Z"));
        l.setCodeVersion(LabelConfig.CODE_VERSION);
        l.setRowCount(28800);
        l.setCreatedAt(TO);
        long[] t = {FROM.toEpochMilli(), FROM.toEpochMilli() + 60_000, TO.toEpochMilli() - 60_000};
        double[] p = {100, 101, 99};
        LabelCalculator.Result r = LabelCalculator.compute(t, p, p, p, p, labelConfig());
        LabelSummary.MarketSummary ms = LabelSummary.summarize(1, "BTC · 업비트 KRW-BTC", t, r, 0, l.getExploreTo().toEpochMilli());
        l.setSummary(Json.write(new LabelSummary(List.of(ms), List.of("경고 예시"))));
        return l;
    }

    static SplitConfig splitConfig() {
        return new SplitConfig(null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6),
                LocalDate.of(2026, 1, 7), LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 10), 31);
    }

    static SplitPlan split() {
        SplitPlan p = new SplitPlan();
        p.setId(4L);
        p.setName("첫 분할");
        p.setStatus(BuildStatus.READY);
        p.setLabelSetId(3L);
        p.setConfig(Json.write(splitConfig()));
        p.setCodeVersion(SplitConfig.CODE_VERSION);
        p.setCreatedAt(TO);
        return p;
    }

    static Map<Long, String> marketLabels() {
        Map<Long, String> m = new LinkedHashMap<>();
        m.put(1L, "BTC · 업비트 KRW-BTC");
        m.put(2L, "ETH · 업비트 KRW-ETH");
        return m;
    }

    @Test
    void home_and_lists() {
        Map<String, Object> m = base("home");
        m.put("raws", List.of(raw()));
        m.put("features", List.of(feature()));
        m.put("labels", List.of(label()));
        m.put("splits", List.of(split()));
        String home = render("home", m);
        assertThat(home).contains("자동 전략 탐색 V2", "data-term=\"v2Flow\"", "nav-item active", "후속 설계");

        m = base("raw");
        m.put("raws", List.of(raw()));
        m.put("marketLabels", marketLabels());
        m.put("featureCounts", Map.of(1L, 1));
        assertThat(render("raw/list", m)).contains("업비트 BTC·ETH 10일", "2026-01-01 ~ 2026-01-10", "지표 만들기 →");

        m = base("features");
        m.put("features", List.of(feature()));
        m.put("rawNames", Map.of(1L, "원본"));
        m.put("labelCounts", Map.of(2L, 0));
        assertThat(render("features/list", m)).contains("기본 지표", IndicatorPlanner.CODE_VERSION);

        m = base("labels");
        m.put("labels", List.of(label()));
        m.put("configs", Map.of(3L, labelConfig()));
        m.put("featureNames", Map.of(2L, "기본 지표"));
        m.put("splitCounts", Map.of(3L, 0));
        assertThat(render("labels/list", m)).contains("30분", "0.5%", "long · 다음 시가 · 동시=불가 · 비용");

        m = base("splits");
        m.put("splits", List.of(split()));
        m.put("configs", Map.of(4L, splitConfig()));
        m.put("labelNames", Map.of(3L, "정답"));
        assertThat(render("splits/list", m)).contains("2026-01-01 ~ 2026-01-05", "31분", "0회");
    }

    @Test
    void raw_pages() {
        Map<String, Object> m = base("raw");
        m.put("assets", List.of());
        m.put("exchanges", List.of(Exchange.values()));
        m.put("defaultFrom", LocalDate.of(2026, 1, 1));
        m.put("defaultTo", LocalDate.of(2026, 1, 7));
        m.put("maxDate", LocalDate.of(2026, 1, 7));
        assertThat(render("raw/new", m)).contains("업비트", "바이낸스", "/js/raw-new.js", "jobBox");

        RawDataset d = raw();
        m = base("raw");
        m.put("d", d);
        m.put("quality", com.mch.strategy.raw.RawService.quality(d));
        m.put("marketLabels", marketLabels());
        m.put("children", List.of(feature()));
        m.put("job", null);
        String html = render("raw/detail", m);
        assertThat(html).contains("입력 검사", "KRW-BTC", "빈 1분 목록", "확인할 점");
    }

    @Test
    void feature_pages() {
        Map<String, Object> m = base("features");
        m.put("raws", List.of(raw()));
        m.put("rawQualityJson", "{}");
        m.put("configJson", Json.write(IndicatorConfig.defaults()));
        m.put("defaultsJson", Json.write(IndicatorConfig.defaults()));
        m.put("selectedRaw", 1L);
        m.put("initialName", "");
        m.put("bundlesJson", Json.write(IndicatorPlanner.BUNDLES));
        m.put("kinds", IndicatorKind.values());
        m.put("marketLabels", marketLabels());
        m.put("codeVersion", IndicatorPlanner.CODE_VERSION);
        String html = render("features/new", m);
        assertThat(html).contains("data-path=\"volume.periods\"", "data-path=\"ma.pairScope\"", "data-hover-term=\"b_RSI_stoch\"",
                "data-path=\"bb.multipliers\"", "data-term=\"ind_MACD\"", "/js/feature-new.js", "sticky-summary");

        m = base("features");
        m.put("f", feature());
        m.put("raw", raw());
        m.put("columnsJson", "[]");
        m.put("configJson", Json.write(IndicatorConfig.defaults()));
        m.put("marketIds", List.of(1L, 2L));
        m.put("marketLabels", marketLabels());
        m.put("marketLabelsJson", Json.write(marketLabels()));
        m.put("kinds", IndicatorKind.values());
        m.put("children", List.of(label()));
        m.put("job", null);
        assertThat(render("features/detail", m)).contains("행 보기", "CSV 내보내기", "중복 제거 전 12", "/js/feature-detail.js");
    }

    @Test
    void label_pages() {
        Map<String, Object> m = base("labels");
        m.put("features", List.of(feature()));
        m.put("featureInfoJson", "{}");
        m.put("configJson", "null");
        m.put("initialExploreTo", null);
        m.put("initialName", "");
        m.put("selectedFeature", 2L);
        m.put("outcomes", LabelOutcome.values());
        assertThat(render("labels/new", m)).contains("H 최대 관찰 시간", "data-term=\"sameBar\"", "탐색 기간 끝", "/js/label-new.js");

        LabelSet l = label();
        m = base("labels");
        m.put("l", l);
        m.put("f", feature());
        m.put("raw", raw());
        m.put("c", labelConfig());
        m.put("summaryJson", l.getSummary());
        m.put("marketIds", List.of(1L));
        m.put("marketLabels", marketLabels());
        m.put("outcomes", LabelOutcome.values());
        m.put("children", List.of(split()));
        m.put("job", null);
        assertThat(render("labels/detail", m)).contains("H 30분", "P 0.5%", "비용 반영", "왕복 0.14", "판정 종료");
    }

    @Test
    void split_pages() {
        Map<String, Object> m = base("splits");
        m.put("labels", List.of(label()));
        m.put("labelInfoJson", "{}");
        m.put("configJson", "null");
        m.put("initialName", "");
        m.put("selectedLabel", 3L);
        m.put("reasons", SplitCalculator.Reason.values());
        assertThat(render("splits/new", m)).contains("id=\"trainFrom\"", "data-term=\"embargo\"", "/js/split-new.js");

        m = base("splits");
        m.put("p", split());
        m.put("l", label());
        m.put("f", feature());
        m.put("raw", raw());
        m.put("lc", labelConfig());
        m.put("c", splitConfig());
        m.put("summaryJson", "null");
        m.put("testViewLog", List.of(TO));
        String html = render("splits/detail", m);
        assertThat(html).contains("재현 경로", "원본 #1", "지표 #2", "정답 #3", "2026-01-01 ~ 2026-01-05", "최종 테스트 분포 열기", "\"2026-01-09\"");
    }
}
