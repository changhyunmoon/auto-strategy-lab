package com.mch.strategy.label;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 정답 분포 요약. 탐색 기간(exploreTo 이전)은 상태별 건수를 모두 보여주고,
 * 숨김 기간(exploreTo 이후, 최종 테스트 후보)은 행 수와 데이터 품질 관련 건수만 둔다.
 */
public record LabelSummary(List<MarketSummary> markets, List<String> warnings) {

    /**
     * @param explore 탐색 기간 집계
     * @param hidden  숨김 기간: rows · GAP · NO_FUTURE 만 (나머지는 「판정됨(숨김)」 한 줄)
     */
    public record MarketSummary(long marketId, String label, Period explore, Period hidden) {
    }

    /**
     * @param counts            상태 이름 → 건수
     * @param avgBarsToEvent    목표 · 손실 도달까지 평균 몇 분 걸렸는지
     * @param avgTimeoutReturn  시간 만료 행의 평균 수익률(%)
     */
    public record Period(long rows, Map<String, Long> counts, Double avgBarsToEvent, Double avgTimeoutReturn) {
    }

    /** 결과 배열을 판단 시각으로 나눠 집계한다 */
    public static MarketSummary summarize(long marketId, String label, long[] time, LabelCalculator.Result r,
                                          long exploreFrom, long exploreTo) {
        Acc e = new Acc();
        Acc h = new Acc();
        for (int i = 0; i < time.length; i++) {
            if (time[i] < exploreFrom) continue;
            (time[i] < exploreTo ? e : h).add(r, i);
        }
        Map<String, Long> hiddenCounts = new LinkedHashMap<>();
        long judged = 0;
        for (LabelOutcome o : LabelOutcome.values()) {
            long c = h.counts.getOrDefault(o.name(), 0L);
            if (o == LabelOutcome.GAP || o == LabelOutcome.NO_FUTURE) hiddenCounts.put(o.name(), c);
            else judged += c;
        }
        hiddenCounts.put("HIDDEN", judged);
        return new MarketSummary(marketId, label, e.period(), new Period(h.rows, hiddenCounts, null, null));
    }

    /** 경고: 탐색 기간에 한 상태가 거의 없음 · 판정 불가 · 데이터 부족이 많음. 기준을 자동으로 바꾸지 않는다. */
    public static List<String> warnings(List<MarketSummary> markets) {
        List<String> w = new ArrayList<>();
        for (MarketSummary m : markets) {
            Period p = m.explore();
            if (p.rows() == 0) {
                w.add(m.label() + ": 탐색 기간에 행이 없습니다.");
                continue;
            }
            long valid = 0;
            for (LabelOutcome o : List.of(LabelOutcome.TARGET, LabelOutcome.STOP, LabelOutcome.TIMEOUT)) {
                valid += p.counts().getOrDefault(o.name(), 0L);
            }
            for (LabelOutcome o : List.of(LabelOutcome.TARGET, LabelOutcome.STOP, LabelOutcome.TIMEOUT)) {
                long c = p.counts().getOrDefault(o.name(), 0L);
                if (valid > 0 && (c < 30 || (double) c / valid < 0.01)) {
                    w.add(m.label() + ": 「" + o.getLabel() + "」이 " + c + "건으로 매우 적습니다. 모델이 이 경우를 배우기 어렵습니다.");
                }
            }
            long amb = p.counts().getOrDefault(LabelOutcome.AMBIGUOUS.name(), 0L);
            long gap = p.counts().getOrDefault(LabelOutcome.GAP.name(), 0L) + p.counts().getOrDefault(LabelOutcome.NO_FUTURE.name(), 0L);
            if ((double) amb / p.rows() > 0.05) {
                w.add(m.label() + ": 같은 1분봉 동시 도달이 " + pct(amb, p.rows()) + " 입니다. P · L 이 1분 변동 폭에 비해 작을 수 있습니다.");
            }
            if ((double) gap / p.rows() > 0.05) {
                w.add(m.label() + ": 빈 1분 · 데이터 부족으로 판정 못 한 행이 " + pct(gap, p.rows()) + " 입니다.");
            }
        }
        return w;
    }

    private static String pct(long a, long b) {
        return String.format("%.1f%%", 100.0 * a / Math.max(1, b));
    }

    private static final class Acc {
        long rows;
        final Map<String, Long> counts = new LinkedHashMap<>();
        long eventBars;
        long events;
        double timeoutSum;
        long timeouts;

        Acc() {
            for (LabelOutcome o : LabelOutcome.values()) counts.put(o.name(), 0L);
        }

        void add(LabelCalculator.Result r, int i) {
            rows++;
            LabelOutcome o = LabelOutcome.of(r.status[i]);
            counts.merge(o.name(), 1L, Long::sum);
            if (o == LabelOutcome.TARGET || o == LabelOutcome.STOP) {
                eventBars += r.bars[i];
                events++;
            }
            if (o == LabelOutcome.TIMEOUT && !Double.isNaN(r.timeoutReturn[i])) {
                timeoutSum += r.timeoutReturn[i];
                timeouts++;
            }
        }

        Period period() {
            return new Period(rows, counts, events == 0 ? null : (double) eventBars / events,
                    timeouts == 0 ? null : timeoutSum / timeouts);
        }
    }
}
