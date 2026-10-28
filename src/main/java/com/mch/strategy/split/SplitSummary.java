package com.mch.strategy.split;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분할 요약. segments 의 키는 TRAIN · VAL · TEST · NONE(어느 구간에도 속하지 않은 행).
 * TEST 의 labels 는 저장은 하되 화면에는 「최종 테스트 분포 열기」를 눌렀을 때만 보낸다 ({@link #hideTest()}).
 */
public record SplitSummary(List<MarketSplit> markets, List<String> warnings, Boundaries boundaries, List<TimelineItem> timeline) {

    public record MarketSplit(long marketId, String label, Map<String, SegView> segments) {
    }

    public record SegView(long rows, long valid, long validWithNullInputs, Map<String, Long> reasons, Map<String, Long> labels) {
    }

    /** 실제 적용된 UTC 경계 [from, to) */
    public record Boundaries(Instant useFrom, Instant useTo, Instant trainFrom, Instant trainTo, Instant valFrom, Instant valTo,
                             Instant testFrom, Instant testTo, int embargoMinutes) {
    }

    /** 시간순 목록 한 줄: 구간 · 빈 기간 · 원본 빈 1분 */
    public record TimelineItem(Instant from, Instant to, String kind, String text) {
    }

    public SplitSummary hideTest() {
        return new SplitSummary(markets.stream().map(m -> {
            Map<String, SegView> seg = new LinkedHashMap<>();
            m.segments().forEach((k, v) -> seg.put(k, "TEST".equals(k)
                    ? new SegView(v.rows(), v.valid(), v.validWithNullInputs(), v.reasons(), null) : v));
            return new MarketSplit(m.marketId(), m.label(), seg);
        }).toList(), warnings, boundaries, timeline);
    }
}
