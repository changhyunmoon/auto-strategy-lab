package com.mch.strategy.trainset;

/**
 * 학습 행 만들기 규칙 (DB 와 무관한 계산만 모았다).
 * <ul>
 *   <li>정답: 현재 봉 종가 → 정확히 다음 봉 종가의 수익률(%)을 기준 %로 상승·중립·하락으로 나눈다.</li>
 *   <li>구간: 판단 시각이 속한 구간(학습·검증·테스트). 다음 봉이 구간 끝을 넘으면 그 행은 버린다.</li>
 *   <li>다른 간격 지표: 판단 시각에 이미 끝난 마지막 봉만 붙인다 (진행 중인 봉은 쓰지 않는다).</li>
 * </ul>
 */
public final class TrainingRows {

    /** 화면에서 고를 수 있는 정답 기준 % */
    public static final double[] THRESHOLDS = {0.1, 0.3, 0.5, 0.7, 1.0};

    /** 파일의 정답 값. 하락 0 · 중립 1 · 상승 2 */
    public static final int DOWN = 0, NEUTRAL = 1, UP = 2;

    public static final String OUTSIDE = "outside";            // 어느 구간에도 속하지 않는 날짜
    public static final String NO_NEXT_BAR = "noNextBar";      // 다음 봉이 없거나 빈 구간 뒤에 있음
    public static final String CROSSES_BOUNDARY = "crossesBoundary"; // 다음 봉이 구간 끝을 넘음
    public static final String MISSING_PRICE = "missingPrice"; // 종가가 비어 있음

    private TrainingRows() {
    }

    public static int label(double forwardPct, double thresholdPct) {
        if (forwardPct >= thresholdPct) {
            return UP;
        }
        if (forwardPct <= -thresholdPct) {
            return DOWN;
        }
        return NEUTRAL;
    }

    /**
     * @param ranges ranges[s] = {시작, 끝) epoch ms. s = 학습 0 · 검증 1 · 테스트 2
     * @return 행마다의 구간 · 다음 봉 수익률 · 직전 봉 수익률 (제외된 행은 split = -1 과 사유)
     */
    public static Plan[] plan(long[] time, double[] close, long intervalMs, long[][] ranges) {
        Plan[] out = new Plan[time.length];
        for (int i = 0; i < time.length; i++) {
            long t = time[i];
            int split = -1;
            for (int s = 0; s < ranges.length; s++) {
                if (t >= ranges[s][0] && t < ranges[s][1]) {
                    split = s;
                    break;
                }
            }
            double prev = i > 0 && time[i - 1] == t - intervalMs ? pct(close[i - 1], close[i]) : Double.NaN;
            if (split < 0) {
                out[i] = Plan.excluded(OUTSIDE);
            } else if (i + 1 >= time.length || time[i + 1] != t + intervalMs) {
                out[i] = Plan.excluded(NO_NEXT_BAR);
            } else if (t + 2 * intervalMs > ranges[split][1]) {
                out[i] = Plan.excluded(CROSSES_BOUNDARY);
            } else {
                double fwd = pct(close[i], close[i + 1]);
                out[i] = Double.isNaN(fwd) ? Plan.excluded(MISSING_PRICE) : new Plan(split, null, fwd, prev);
            }
        }
        return out;
    }

    /**
     * 판단 봉마다, 그 봉이 끝나는 시각에 이미 끝난 다른 간격 봉의 위치를 찾는다.
     * 바로 앞 봉이 빈 구간 때문에 없으면 오래된 값을 붙이지 않고 -1 로 둔다.
     */
    public static int[] alignCompleted(long[] decisionTime, long decisionMs, long[] otherTime, long otherMs) {
        int[] out = new int[decisionTime.length];
        int j = -1;
        for (int i = 0; i < decisionTime.length; i++) {
            long decisionEnd = decisionTime[i] + decisionMs;
            while (j + 1 < otherTime.length && otherTime[j + 1] + otherMs <= decisionEnd) {
                j++;
            }
            boolean fresh = j >= 0 && decisionEnd - (otherTime[j] + otherMs) < otherMs;
            out[i] = fresh ? j : -1;
        }
        return out;
    }

    static double pct(double from, double to) {
        if (Double.isNaN(from) || Double.isNaN(to) || from <= 0) {
            return Double.NaN;
        }
        return (to / from - 1) * 100;
    }

    /** split: 0·1·2 또는 제외 -1. forwardPct · prevPct 는 % 단위 */
    public record Plan(int split, String excludedReason, double forwardPct, double prevPct) {
        static Plan excluded(String reason) {
            return new Plan(-1, reason, Double.NaN, Double.NaN);
        }
    }
}
