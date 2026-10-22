package com.mch.strategy.candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 1분봉 누락 구간을 찾는다. 빈 구간은 값을 채우지 않고 기록만 한다. */
public final class QualityChecker {

    /** 화면에 보여줄 빈 구간 최대 개수 (가장 긴 순) */
    public static final int MAX_GAPS = 100;

    private QualityChecker() {
    }

    /**
     * @param time  [from, to) 안의 1분봉 시작 시각(오름차순, 밀리초)
     * @return 예상 분 수, 실제 분 수, 빈 구간 목록(긴 순 최대 100개)
     */
    public static Result check(long[] time, Instant from, Instant to) {
        long fromMs = from.toEpochMilli();
        long toMs = to.toEpochMilli();
        long expected = (toMs - fromMs) / 60_000L;
        List<Gap> gaps = new ArrayList<>();
        long prev = fromMs - 60_000L;
        long actual = 0;
        for (long t : time) {
            if (t < fromMs || t >= toMs) {
                continue;
            }
            actual++;
            addGap(gaps, prev + 60_000L, t);
            prev = t;
        }
        addGap(gaps, prev + 60_000L, toMs);
        long missingTotal = gaps.stream().mapToLong(Gap::minutes).sum();
        gaps.sort((a, b) -> Long.compare(b.minutes(), a.minutes()));
        List<Gap> top = gaps.size() > MAX_GAPS ? new ArrayList<>(gaps.subList(0, MAX_GAPS)) : gaps;
        top.sort((a, b) -> a.from().compareTo(b.from()));
        return new Result(expected, actual, gaps.size(), missingTotal, top);
    }

    private static void addGap(List<Gap> gaps, long gapStart, long nextBar) {
        if (nextBar > gapStart) {
            gaps.add(new Gap(Instant.ofEpochMilli(gapStart), Instant.ofEpochMilli(nextBar),
                    (nextBar - gapStart) / 60_000L));
        }
    }

    public record Gap(Instant from, Instant to, long minutes) {
    }

    public record Result(long expectedMinutes, long actualMinutes, int gapCount, long missingMinutes, List<Gap> gaps) {
    }
}
