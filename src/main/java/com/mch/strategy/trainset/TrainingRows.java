package com.mch.strategy.trainset;

/**
 * 학습 행 만들기 규칙 (DB 와 무관한 계산만 모았다).
 * <ul>
 *   <li>정답: {@link LabelSpec} 에 따라 N봉 뒤 종가 수익률 또는 N봉 안의 목표가·손절가 도달로 상승·중립·하락을 정한다.</li>
 *   <li>구간: 판단 시각이 속한 구간(학습·검증·테스트). 정답에 쓰는 N봉이 구간 끝을 넘으면 그 행은 버린다 (정보 누출 방지).</li>
 *   <li>다른 간격 지표: 판단 시각에 이미 끝난 마지막 봉만 붙인다 (진행 중인 봉은 쓰지 않는다).</li>
 * </ul>
 */
public final class TrainingRows {

    /** 예전 화면 호환용 고정 기준 목록 */
    public static final double[] THRESHOLDS = {0.1, 0.3, 0.5, 0.7, 1.0};

    /** 파일의 정답 값. 하락 0 · 중립 1 · 상승 2 */
    public static final int DOWN = 0, NEUTRAL = 1, UP = 2;
    /** 목표가 · 손절가에 같은 봉에서 동시에 닿음 (어느 쪽이 먼저인지 알 수 없어 중립으로 둔다) */
    public static final int AMBIGUOUS = 3;

    public static final String OUTSIDE = "outside";            // 어느 구간에도 속하지 않는 날짜
    public static final String NO_NEXT_BAR = "noNextBar";      // 예측 기간 N봉 안에 빈 봉이 있거나 데이터 끝
    public static final String CROSSES_BOUNDARY = "crossesBoundary"; // 예측 기간이 구간 끝을 넘음
    public static final String MISSING_PRICE = "missingPrice"; // 가격이 비어 있음
    public static final String NO_VOLATILITY = "noVolatility"; // 변동성 기준인데 과거 봉이 부족함

    private TrainingRows() {
    }

    /** 고정 기준으로 수익률(%)을 나눈다 */
    public static int label(double returnPct, double thresholdPct) {
        if (returnPct >= thresholdPct) {
            return UP;
        }
        if (returnPct <= -thresholdPct) {
            return DOWN;
        }
        return NEUTRAL;
    }

    /**
     * @param ranges ranges[s] = {시작, 끝) epoch ms. s = 학습 0 · 검증 1 · 테스트 2
     * @return 행마다의 구간 · 정답 · N봉 뒤 수익률 · 기준 · 직전 N봉 방향 (제외된 행은 split = -1 과 사유)
     */
    public static Plan[] plan(Calculator calc, long[][] ranges, LabelSpec spec) {
        int n = calc.time.length;
        int h = spec.horizon();
        Plan[] out = new Plan[n];
        for (int i = 0; i < n; i++) {
            long t = calc.time[i];
            int split = -1;
            for (int s = 0; s < ranges.length; s++) {
                if (t >= ranges[s][0] && t < ranges[s][1]) {
                    split = s;
                    break;
                }
            }
            if (split < 0) {
                out[i] = Plan.excluded(OUTSIDE);
            } else if (!calc.contiguousAhead(i, h)) {
                out[i] = Plan.excluded(NO_NEXT_BAR);
            } else if (t + (long) (h + 1) * calc.intervalMs > ranges[split][1]) {
                out[i] = Plan.excluded(CROSSES_BOUNDARY);
            } else {
                double thr = calc.threshold(i, spec);
                double fwd = calc.forwardPct(i, h);
                if (Double.isNaN(fwd)) {
                    out[i] = Plan.excluded(MISSING_PRICE);
                } else if (Double.isNaN(thr)) {
                    out[i] = Plan.excluded(NO_VOLATILITY);
                } else {
                    int label = calc.label(i, spec, thr);
                    out[i] = new Plan(split, null, label == AMBIGUOUS ? NEUTRAL : label, label == AMBIGUOUS,
                            fwd, thr, calc.previousLabel(i, h, thr));
                }
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

    /**
     * split: 0·1·2 또는 제외 -1. label 은 동시 도달이면 중립(ambiguous = true).
     * forwardPct = N봉 뒤 종가 수익률(%), thresholdPct = 이 행의 기준(%),
     * previousLabel = 직전 N봉 수익률을 같은 기준으로 나눈 방향 (없으면 NaN) — 「직전 봉 방향 유지」 기준 모델용
     */
    public record Plan(int split, String excludedReason, int label, boolean ambiguous,
                       double forwardPct, double thresholdPct, double previousLabel) {
        static Plan excluded(String reason) {
            return new Plan(-1, reason, -1, false, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    /** 한 자산 · 한 간격의 봉 배열로 정답에 필요한 값을 계산한다 (과거 값만으로 변동성 계산) */
    public static final class Calculator {
        final long[] time;
        final double[] close;
        final double[] high;
        final double[] low;
        final long intervalMs;
        /** 변동성 창 크기 → 행마다 1봉 로그 수익률 표준편차(%) */
        private final java.util.Map<Integer, double[]> sigmaCache = new java.util.HashMap<>();

        public Calculator(long[] time, double[] close, double[] high, double[] low, long intervalMs) {
            this.time = time;
            this.close = close;
            this.high = high;
            this.low = low;
            this.intervalMs = intervalMs;
        }

        boolean contiguousAhead(int i, int h) {
            return i + h < time.length && time[i + h] == time[i] + (long) h * intervalMs;
        }

        double forwardPct(int i, int h) {
            return pct(close[i], close[i + h]);
        }

        double threshold(int i, LabelSpec spec) {
            return spec.threshold(spec.needsVolatility() ? sigma(spec.volWindow())[i] : 0);
        }

        /** 정답. 목표가·손절가 방식에서 같은 봉에 둘 다 닿으면 AMBIGUOUS */
        int label(int i, LabelSpec spec, double thr) {
            int h = spec.horizon();
            if (spec.method().equals(LabelSpec.TRIPLE_BARRIER)) {
                double upPrice = close[i] * (1 + thr / 100), downPrice = close[i] * (1 - thr / 100);
                for (int k = 1; k <= h; k++) {
                    boolean up = high[i + k] >= upPrice, down = low[i + k] <= downPrice;
                    if (up && down) {
                        return AMBIGUOUS;
                    }
                    if (up) {
                        return UP;
                    }
                    if (down) {
                        return DOWN;
                    }
                }
                return NEUTRAL;
            }
            return TrainingRows.label(forwardPct(i, h), thr);
        }

        /** 다른 기준 표용: 같은 행을 다른 정답 정의로 셌을 때 (계산 불가면 -1) */
        public int labelFor(int i, LabelSpec spec) {
            double thr = threshold(i, spec);
            if (Double.isNaN(thr)) {
                return -1;
            }
            int l = label(i, spec, thr);
            return l == AMBIGUOUS ? NEUTRAL : l;
        }

        double previousLabel(int i, int h, double thr) {
            if (i - h < 0 || time[i - h] != time[i] - (long) h * intervalMs) {
                return Double.NaN;
            }
            double r = pct(close[i - h], close[i]);
            return Double.isNaN(r) ? Double.NaN : TrainingRows.label(r, thr);
        }

        /** 직전 window 개의 1봉 로그 수익률 표준편차(%). 빈 봉을 건넌 수익률은 빼고, 절반 미만이면 NaN */
        double[] sigma(int window) {
            return sigmaCache.computeIfAbsent(window, w -> {
                int n = time.length;
                double[] sum = new double[n + 1], sq = new double[n + 1];
                int[] cnt = new int[n + 1];
                for (int k = 0; k < n; k++) {
                    double r = Double.NaN;
                    if (k > 0 && time[k] - time[k - 1] == intervalMs && close[k - 1] > 0 && close[k] > 0) {
                        r = Math.log(close[k] / close[k - 1]) * 100;
                    }
                    boolean ok = Double.isFinite(r);
                    sum[k + 1] = sum[k] + (ok ? r : 0);
                    sq[k + 1] = sq[k] + (ok ? r * r : 0);
                    cnt[k + 1] = cnt[k] + (ok ? 1 : 0);
                }
                double[] out = new double[n];
                for (int k = 0; k < n; k++) {
                    int from = Math.max(0, k + 1 - w);
                    int c = cnt[k + 1] - cnt[from];
                    if (c < Math.max(2, w / 2)) {
                        out[k] = Double.NaN;
                        continue;
                    }
                    double mean = (sum[k + 1] - sum[from]) / c;
                    double var = (sq[k + 1] - sq[from]) / c - mean * mean;
                    out[k] = Math.sqrt(Math.max(0, var));
                }
                return out;
            });
        }
    }
}
