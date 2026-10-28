package com.mch.strategy.raw;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mch.strategy.collect.CandleSeries;

/**
 * 원본 1분봉 입력 검사 (노션 「기술 지표 생성 화면」 3절).
 * 값을 고치거나 채우지 않고 개수 · 위치만 기록한다. 빈 1분이 있으면 그 앞뒤는 지표 · 정답 계산에서 다른 구간으로 다룬다.
 */
public final class RawQuality {

    public static final long MINUTE = 60_000L;
    /** 화면에 보여줄 빈 구간 최대 개수 (긴 순) */
    public static final int MAX_GAPS = 50;

    private RawQuality() {
    }

    public record Gap(Instant from, Instant to, long minutes) {
    }

    /**
     * 거래쌍 하나의 검사 결과.
     *
     * @param blockers 이 중 하나라도 있으면 지표 생성을 막는다
     * @param warnings 생성은 가능하지만 확인할 점
     */
    public record MarketQuality(
            long marketId, String assetCode, String symbol, String quoteCurrency,
            long rows, Instant first, Instant last,
            long expectedMinutes, long missingMinutes, int gapCount, long longestGapMinutes, List<Gap> gaps,
            int segments, long longestSegment,
            long misaligned, long outOfOrder, long duplicates, long ohlcErrors, long negativeVolume, long zeroVolume,
            long incomplete, boolean quoteVolumePresent,
            List<String> blockers, List<String> warnings
    ) {
        public double fillRate() {
            return expectedMinutes == 0 ? 0 : (double) rows / expectedMinutes;
        }
    }

    /**
     * @param from  검사 범위 시작(포함)
     * @param to    검사 범위 끝(제외)
     * @param now   봉 완료 여부 판단 기준 시각 (시작 + 1분이 now 이후면 미완료)
     */
    public static MarketQuality check(long marketId, String assetCode, String symbol, String quoteCurrency,
                                      CandleSeries s, Instant from, Instant to, Instant now) {
        long[] t = s.time();
        int n = t.length;
        long fromMs = from.toEpochMilli();
        long toMs = to.toEpochMilli();
        long expected = (toMs - fromMs) / MINUTE;

        long misaligned = 0, outOfOrder = 0, duplicates = 0, ohlc = 0, negVol = 0, zeroVol = 0, incomplete = 0;
        boolean quotePresent = n > 0;
        List<Gap> gaps = new ArrayList<>();
        long missing = 0;
        long prev = fromMs - MINUTE;
        int segments = 0;
        long segLen = 0, longestSeg = 0;
        for (int i = 0; i < n; i++) {
            long ti = t[i];
            if (ti % MINUTE != 0) misaligned++;
            if (i > 0 && ti < t[i - 1]) outOfOrder++;
            if (i > 0 && ti == t[i - 1]) duplicates++;
            double o = s.open()[i], h = s.high()[i], l = s.low()[i], c = s.close()[i], v = s.volume()[i];
            if (h < Math.max(o, c) || l > Math.min(o, c) || l > h || l <= 0) ohlc++;
            if (v < 0) negVol++;
            if (v == 0) zeroVol++;
            if (ti + MINUTE > now.toEpochMilli()) incomplete++;
            if (Double.isNaN(s.quoteVolume()[i])) quotePresent = false;

            if (ti > prev + MINUTE) {
                long mins = (ti - prev - MINUTE) / MINUTE;
                missing += mins;
                gaps.add(new Gap(Instant.ofEpochMilli(prev + MINUTE), Instant.ofEpochMilli(ti), mins));
                if (segLen > 0) {
                    segments++;
                    longestSeg = Math.max(longestSeg, segLen);
                }
                segLen = 0;
            }
            segLen++;
            prev = Math.max(prev, ti);
        }
        if (segLen > 0) {
            segments++;
            longestSeg = Math.max(longestSeg, segLen);
        }
        if (prev + MINUTE < toMs) {
            long mins = (toMs - prev - MINUTE) / MINUTE;
            missing += mins;
            gaps.add(new Gap(Instant.ofEpochMilli(prev + MINUTE), Instant.ofEpochMilli(toMs), mins));
        }
        int gapCount = gaps.size();
        long longestGap = gaps.stream().mapToLong(Gap::minutes).max().orElse(0);
        gaps.sort((a, b) -> Long.compare(b.minutes(), a.minutes()));
        List<Gap> top = new ArrayList<>(gaps.subList(0, Math.min(MAX_GAPS, gaps.size())));
        top.sort((a, b) -> a.from().compareTo(b.from()));

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (n == 0) blockers.add("이 기간에 1분봉이 하나도 없습니다.");
        if (misaligned > 0) blockers.add("1분 경계(초 = 0)에 맞지 않는 시각이 " + misaligned + "개 있습니다. 1분봉이 아닌 데이터일 수 있습니다.");
        if (n > 0 && incomplete == n) blockers.add("모든 봉이 아직 끝나지 않은(미완료) 봉입니다.");
        if (outOfOrder > 0 || duplicates > 0) blockers.add("시간 역순 " + outOfOrder + "개 · 같은 시각 중복 " + duplicates + "개가 있습니다.");
        if (ohlc > 0) warnings.add("고가 · 저가 범위가 맞지 않는 봉이 " + ohlc + "개 있습니다 (고가 < 시가·종가, 저가 > 시가·종가 등). 값을 고치지 않고 그대로 씁니다.");
        if (negVol > 0) warnings.add("거래량이 음수인 봉이 " + negVol + "개 있습니다.");
        if (incomplete > 0 && incomplete < n) warnings.add("아직 끝나지 않은 봉 " + incomplete + "개가 있습니다.");
        if (missing > 0) warnings.add("빈 1분이 " + missing + "분 (" + gapCount + "곳) 있습니다. 빈 곳 앞뒤는 지표를 새로 시작하고, 정답 관찰 구간이 걸치면 판정 불가로 둡니다.");
        if (!quotePresent && n > 0) warnings.add("거래대금(quote_volume)이 없는 봉이 있습니다. 여섯 지표는 OHLCV 만으로 만들 수 있습니다.");

        return new MarketQuality(marketId, assetCode, symbol, quoteCurrency, n,
                n == 0 ? null : Instant.ofEpochMilli(t[0]), n == 0 ? null : Instant.ofEpochMilli(t[n - 1]),
                expected, missing, gapCount, longestGap, top, segments, longestSeg,
                misaligned, outOfOrder, duplicates, ohlc, negVol, zeroVol, incomplete, quotePresent, blockers, warnings);
    }

    /** 1분이 끊기는 곳에서 나눈 구간들의 [시작, 끝) 인덱스 */
    public static List<int[]> segments(long[] time) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= time.length; i++) {
            if (i == time.length || time[i] != time[i - 1] + MINUTE) {
                if (i > start) out.add(new int[]{start, i});
                start = i;
            }
        }
        return out;
    }
}
