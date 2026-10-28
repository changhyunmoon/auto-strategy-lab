package com.mch.strategy.label;

import java.util.Arrays;

/**
 * 판단 시점마다 H · P · L 정답을 계산한다. 1분봉을 그대로 쓰고 상위 봉으로 묶지 않는다.
 *
 * <p>판정 순서 (노션 「H·P·L 수익 기회 정답 생성 화면」 4절):
 * <ol>
 *   <li>판단 시점 = 행 i 의 1분봉이 끝난 순간. 입력(X)은 여기까지만 쓴다.</li>
 *   <li>진입: NEXT_OPEN 이면 다음 1분봉(i+1) 시가, CLOSE 면 봉 i 의 종가.</li>
 *   <li>관찰 구간: 봉 i+1 ~ i+H (H개의 1분봉). NEXT_OPEN 의 진입 봉 i+1 도 시가 이후 움직임이므로 첫 관찰 봉에 넣는다.</li>
 *   <li>LONG 이면 고가 ≥ 목표가 → 목표 도달, 저가 ≤ 손실가 → 손실 도달 (닿기만 해도 도달로 본다). SHORT 는 반대.</li>
 *   <li>먼저 확인된 사건을 기록한다. 한 봉에서 둘 다면 순서를 알 수 없으므로 동시 도달 규칙을 쓴다.</li>
 *   <li>사건 전에 빈 1분을 만나면 GAP, 데이터 끝을 만나면 NO_FUTURE. 이 둘을 시간 만료로 바꾸지 않는다.</li>
 *   <li>H개 봉을 모두 봤는데 사건이 없으면 TIMEOUT. 이때 마지막 봉 종가로 수익률을 남긴다.</li>
 * </ol>
 * label_end_time = 판정에 쓴 마지막 1분봉이 끝나는 시각 (사건 봉 또는 i+H 봉의 시작 + 1분).
 */
public final class LabelCalculator {

    private static final long MINUTE = 60_000L;

    private LabelCalculator() {
    }

    /**
     * @param time 1분봉 시작 시각(밀리초, 오름차순). 빈 1분은 행이 없다.
     */
    public static Result compute(long[] time, double[] open, double[] high, double[] low, double[] close, LabelConfig c) {
        int n = time.length;
        Result r = new Result(n);
        boolean isLong = c.isLong();
        double tp = c.grossTargetPct() / 100;
        double sl = c.grossStopPct() / 100;
        int h = c.horizon();

        for (int i = 0; i < n; i++) {
            double entry;
            if (c.entryNextOpen()) {
                if (i + 1 >= n) {
                    r.set(i, LabelOutcome.NO_FUTURE, 0, Double.NaN, 0, Double.NaN);
                    continue;
                }
                if (time[i + 1] != time[i] + MINUTE) {
                    r.set(i, LabelOutcome.GAP, 0, Double.NaN, 0, Double.NaN);
                    continue;
                }
                entry = open[i + 1];
            } else {
                entry = close[i];
            }
            double target = isLong ? entry * (1 + tp) : entry * (1 - tp);
            double stop = isLong ? entry * (1 - sl) : entry * (1 + sl);

            LabelOutcome outcome = null;
            int j = i + 1;
            for (; j <= i + h; j++) {
                if (j >= n) {
                    outcome = LabelOutcome.NO_FUTURE;
                    break;
                }
                if (time[j] != time[i] + (long) (j - i) * MINUTE) {
                    outcome = LabelOutcome.GAP;
                    break;
                }
                boolean hitTarget = isLong ? high[j] >= target : low[j] <= target;
                boolean hitStop = isLong ? low[j] <= stop : high[j] >= stop;
                if (hitTarget && hitStop) {
                    outcome = c.stopFirst() ? LabelOutcome.STOP : LabelOutcome.AMBIGUOUS;
                    break;
                }
                if (hitTarget) {
                    outcome = LabelOutcome.TARGET;
                    break;
                }
                if (hitStop) {
                    outcome = LabelOutcome.STOP;
                    break;
                }
            }
            if (outcome == null) {
                int last = i + h;
                double ret = (close[last] / entry - 1) * 100 * (isLong ? 1 : -1);
                r.set(i, LabelOutcome.TIMEOUT, time[last] + MINUTE, entry, h, ret);
            } else if (outcome == LabelOutcome.TARGET || outcome == LabelOutcome.STOP || outcome == LabelOutcome.AMBIGUOUS) {
                r.set(i, outcome, time[j] + MINUTE, entry, j - i, Double.NaN);
            } else {
                r.set(i, outcome, 0, entry, 0, Double.NaN);
            }
        }
        return r;
    }

    /**
     * 행별 결과. 모델 학습의 y 는 status 하나뿐이고, 나머지는 분석 · 감사용 보조 결과다 (입력 X 에 넣지 않는다).
     */
    public static final class Result {
        public final byte[] status;
        /** 판정에 쓴 마지막 1분봉이 끝나는 시각(밀리초). 판정 못 한 행은 0 */
        public final long[] endTime;
        /** 가정 진입 가격 */
        public final double[] entry;
        /** 진입 뒤 사건까지 걸린 1분봉 수 (시간 만료면 H) */
        public final int[] bars;
        /** 시간 만료일 때 마지막 봉 종가 기준 수익률(%, 방향 반영). 그 외 NaN */
        public final double[] timeoutReturn;

        public Result(int n) {
            status = new byte[n];
            endTime = new long[n];
            entry = new double[n];
            bars = new int[n];
            timeoutReturn = new double[n];
            Arrays.fill(timeoutReturn, Double.NaN);
        }

        void set(int i, LabelOutcome o, long end, double entryPrice, int barCount, double ret) {
            status[i] = o.code();
            endTime[i] = end;
            entry[i] = entryPrice;
            bars[i] = barCount;
            timeoutReturn[i] = ret;
        }

        public int size() {
            return status.length;
        }
    }
}
