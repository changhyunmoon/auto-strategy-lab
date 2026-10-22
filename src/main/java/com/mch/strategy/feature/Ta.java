package com.mch.strategy.feature;

import java.util.Arrays;

/**
 * 보조지표 계산용 배열 함수 모음. 모든 함수는 입력과 같은 길이의 배열을 돌려주고,
 * 계산할 수 없는 칸은 NaN 이다. i 번째 값은 0~i 번째 입력만 사용한다 (미래 정보 없음).
 */
public final class Ta {

    private Ta() {
    }

    public static double[] nan(int n) {
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    /** ln(x[i] / x[i - lag]) */
    public static double[] logReturn(double[] x, int lag) {
        double[] out = nan(x.length);
        for (int i = lag; i < x.length; i++) {
            if (x[i] > 0 && x[i - lag] > 0) {
                out[i] = Math.log(x[i] / x[i - lag]);
            }
        }
        return out;
    }

    /** 지수이동평균. 첫 유효값부터 시작하고, 유효값이 n 개 쌓이기 전은 NaN. */
    public static double[] ema(double[] x, int n) {
        double[] out = nan(x.length);
        double k = 2.0 / (n + 1);
        double prev = Double.NaN;
        int valid = 0;
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(x[i])) {
                continue;
            }
            prev = Double.isNaN(prev) ? x[i] : x[i] * k + prev * (1 - k);
            valid++;
            if (valid >= n) {
                out[i] = prev;
            }
        }
        return out;
    }

    /** 최근 n 개 단순평균. 창 안에 NaN 이 있으면 NaN. */
    public static double[] sma(double[] x, int n) {
        double[] out = nan(x.length);
        double sum = 0;
        int nanCount = 0;
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(x[i])) nanCount++; else sum += x[i];
            if (i >= n) {
                if (Double.isNaN(x[i - n])) nanCount--; else sum -= x[i - n];
            }
            if (i >= n - 1 && nanCount == 0) {
                out[i] = sum / n;
            }
        }
        return out;
    }

    public static double[] rollingSum(double[] x, int n) {
        double[] mean = sma(x, n);
        for (int i = 0; i < mean.length; i++) {
            mean[i] *= n;
        }
        return mean;
    }

    /** 최근 n 개 모표준편차. */
    public static double[] rollingStd(double[] x, int n) {
        double[] out = nan(x.length);
        for (int i = n - 1; i < x.length; i++) {
            double sum = 0, sq = 0;
            boolean ok = true;
            for (int j = i - n + 1; j <= i; j++) {
                if (Double.isNaN(x[j])) {
                    ok = false;
                    break;
                }
                sum += x[j];
                sq += x[j] * x[j];
            }
            if (ok) {
                double mean = sum / n;
                out[i] = Math.sqrt(Math.max(0, sq / n - mean * mean));
            }
        }
        return out;
    }

    public static double[] rollingMax(double[] x, int n) {
        double[] out = nan(x.length);
        for (int i = n - 1; i < x.length; i++) {
            double m = Double.NEGATIVE_INFINITY;
            for (int j = i - n + 1; j <= i; j++) m = Math.max(m, x[j]);
            out[i] = m;
        }
        return out;
    }

    public static double[] rollingMin(double[] x, int n) {
        double[] out = nan(x.length);
        for (int i = n - 1; i < x.length; i++) {
            double m = Double.POSITIVE_INFINITY;
            for (int j = i - n + 1; j <= i; j++) m = Math.min(m, x[j]);
            out[i] = m;
        }
        return out;
    }

    /** 최근 n 개 피어슨 상관. 창 안에 NaN 이 하나라도 있으면 NaN. */
    public static double[] rollingCorr(double[] a, double[] b, int n) {
        double[] out = nan(a.length);
        for (int i = n - 1; i < a.length; i++) {
            double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
            boolean ok = true;
            for (int j = i - n + 1; j <= i; j++) {
                if (Double.isNaN(a[j]) || Double.isNaN(b[j])) {
                    ok = false;
                    break;
                }
                sa += a[j]; sb += b[j];
                saa += a[j] * a[j]; sbb += b[j] * b[j]; sab += a[j] * b[j];
            }
            if (!ok) continue;
            double cov = sab / n - (sa / n) * (sb / n);
            double va = saa / n - (sa / n) * (sa / n);
            double vb = sbb / n - (sb / n) * (sb / n);
            if (va > 0 && vb > 0) {
                out[i] = cov / Math.sqrt(va * vb);
            }
        }
        return out;
    }

    /** RSI (Wilder 평활), 0~100. */
    public static double[] rsi(double[] close, int n) {
        double[] out = nan(close.length);
        if (close.length <= n) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = close[i] - close[i - 1];
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        gain /= n;
        loss /= n;
        out[n] = toRsi(gain, loss);
        for (int i = n + 1; i < close.length; i++) {
            double d = close[i] - close[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
            out[i] = toRsi(gain, loss);
        }
        return out;
    }

    private static double toRsi(double gain, double loss) {
        if (loss == 0) return gain == 0 ? 50 : 100;
        return 100 - 100 / (1 + gain / loss);
    }

    /** ATR (Wilder 평활). */
    public static double[] atr(double[] high, double[] low, double[] close, int n) {
        int len = close.length;
        double[] out = nan(len);
        if (len < n) return out;
        double[] tr = new double[len];
        for (int i = 0; i < len; i++) {
            double range = high[i] - low[i];
            tr[i] = i == 0 ? range : Math.max(range, Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
        }
        double atr = 0;
        for (int i = 0; i < n; i++) atr += tr[i];
        atr /= n;
        out[n - 1] = atr;
        for (int i = n; i < len; i++) {
            atr = (atr * (n - 1) + tr[i]) / n;
            out[i] = atr;
        }
        return out;
    }

    /**
     * 교차 상태. diff 의 부호(+1/−1)와, 그 부호가 된 뒤 지난 봉 수.
     * diff 가 정확히 0 이면 직전 부호를 유지한다.
     * @return [0] = 방향, [1] = 경과 봉 수
     */
    public static double[][] crossState(double[] diff) {
        double[] dir = nan(diff.length), age = nan(diff.length);
        double current = Double.NaN;
        int since = 0;
        for (int i = 0; i < diff.length; i++) {
            if (Double.isNaN(diff[i])) {
                current = Double.NaN;
                continue;
            }
            double sign = diff[i] > 0 ? 1 : diff[i] < 0 ? -1 : current;
            if (Double.isNaN(sign)) continue;
            if (sign != current) {
                since = 0;
                current = sign;
            } else {
                since++;
            }
            dir[i] = current;
            age[i] = since;
        }
        return new double[][]{dir, age};
    }

    /** a / b − 1 (b 가 0 이거나 NaN 이면 NaN) */
    public static double[] ratioMinusOne(double[] a, double[] b) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) {
            if (b[i] != 0 && !Double.isNaN(b[i])) out[i] = a[i] / b[i] - 1;
        }
        return out;
    }

    /** a / b (b 가 0 이면 NaN) */
    public static double[] divide(double[] a, double[] b) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) {
            if (b[i] != 0 && !Double.isNaN(b[i])) out[i] = a[i] / b[i];
        }
        return out;
    }

    public static double[] subtract(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - b[i];
        return out;
    }
}
