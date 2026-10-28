package com.mch.strategy.indicator;

import java.util.Arrays;

/**
 * V2 기술 지표 계산 함수. 노션 「V2 — ○○ 지표 생성 규칙」의 계산식을 그대로 옮겼다.
 * <ul>
 *   <li>입력 배열은 끊김 없이 이어진 1분봉 한 구간(세그먼트)이다. 빈 1분이 있으면 그 앞뒤를 다른 구간으로 나눠 따로 계산한다.</li>
 *   <li>값이 없거나 계산할 수 없는 칸은 NaN(= 저장 시 null)이다. 미래 봉은 쓰지 않는다 (i 번째 값은 0..i 만 사용).</li>
 *   <li>NaN 이 앞부분에만 있는 파생 시계열(예: MACD 선)도 받을 수 있다. 첫 유효 값부터 계산을 시작한다.</li>
 * </ul>
 */
public final class Ta {

    private Ta() {
    }

    public static double[] nan(int n) {
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        return out;
    }

    private static int firstValid(double[] x) {
        for (int i = 0; i < x.length; i++) {
            if (!Double.isNaN(x[i])) {
                return i;
            }
        }
        return x.length;
    }

    // ---------------------------------------------------------------- 이동평균

    /** 단순 이동평균: 최근 n개(현재 포함)의 산술평균. 창 안에 NaN 이 있으면 NaN. */
    public static double[] sma(double[] x, int n) {
        int len = x.length;
        double[] out = nan(len);
        double sum = 0;
        int nanCount = 0;
        for (int i = 0; i < len; i++) {
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

    /**
     * 지수 이동평균: alpha = 2/(n+1). 첫 값은 처음 n개 유효 값의 SMA, 그 뒤 EMA[t] = alpha×x[t] + (1−alpha)×EMA[t−1].
     * 첫 유효 값 이전과 n개가 모이기 전은 NaN.
     */
    public static double[] ema(double[] x, int n) {
        int len = x.length;
        double[] out = nan(len);
        int start = firstValid(x);
        int seed = start + n - 1;
        if (seed >= len) {
            return out;
        }
        double sum = 0;
        for (int i = start; i <= seed; i++) {
            if (Double.isNaN(x[i])) {
                return out; // 시작 뒤에 빈 값이 끼면 계산하지 않는다 (입력 구간은 원래 끊김이 없다)
            }
            sum += x[i];
        }
        double alpha = 2.0 / (n + 1);
        double prev = sum / n;
        out[seed] = prev;
        for (int i = seed + 1; i < len; i++) {
            if (Double.isNaN(x[i])) {
                break;
            }
            prev = alpha * x[i] + (1 - alpha) * prev;
            out[i] = prev;
        }
        return out;
    }

    /** 선형 가중 이동평균: 가장 최근 봉 가중치 n, 그 전 n−1, …, 가장 오래된 봉 1. */
    public static double[] wma(double[] x, int n) {
        int len = x.length;
        double[] out = nan(len);
        double denom = n * (n + 1) / 2.0;
        for (int i = n - 1; i < len; i++) {
            double num = 0;
            boolean ok = true;
            for (int k = 0; k < n; k++) {
                double v = x[i - k];
                if (Double.isNaN(v)) {
                    ok = false;
                    break;
                }
                num += v * (n - k);
            }
            if (ok) {
                out[i] = num / denom;
            }
        }
        return out;
    }

    /** 거래량 가중 이동평균: 최근 n개 (close×volume) 합 ÷ volume 합. 거래량 합이 0이면 NaN. */
    public static double[] vwma(double[] close, double[] volume, int n) {
        int len = close.length;
        double[] out = nan(len);
        double pv = 0;
        double v = 0;
        int positive = 0; // 창 안의 거래량 > 0 봉 수. 0이면 합을 정확히 0으로 본다 (빼기 오차 방지)
        for (int i = 0; i < len; i++) {
            pv += close[i] * volume[i];
            v += volume[i];
            if (volume[i] > 0) positive++;
            if (i >= n) {
                pv -= close[i - n] * volume[i - n];
                v -= volume[i - n];
                if (volume[i - n] > 0) positive--;
            }
            if (i >= n - 1 && positive > 0) {
                out[i] = pv / v;
            }
        }
        return out;
    }

    /** 최근 n개 거래량 합. 창 안에 거래량 > 0 봉이 없으면 정확히 0. 창이 덜 찼으면 NaN. */
    public static double[] volumeSum(double[] volume, int n) {
        int len = volume.length;
        double[] out = nan(len);
        double sum = 0;
        int positive = 0;
        for (int i = 0; i < len; i++) {
            sum += volume[i];
            if (volume[i] > 0) positive++;
            if (i >= n) {
                sum -= volume[i - n];
                if (volume[i - n] > 0) positive--;
            }
            if (i >= n - 1) {
                out[i] = positive == 0 ? 0 : sum;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 변화 · 비교

    /** x[t] − x[t−lag]. 어느 쪽이든 NaN 이면 NaN. */
    public static double[] delta(double[] x, int lag) {
        double[] out = nan(x.length);
        for (int i = lag; i < x.length; i++) {
            out[i] = x[i] - x[i - lag];
        }
        return out;
    }

    /** x[t] ÷ x[t−lag] − 1. 분모가 0 이거나 NaN 이면 NaN. */
    public static double[] pctChange(double[] x, int lag) {
        double[] out = nan(x.length);
        for (int i = lag; i < x.length; i++) {
            double b = x[i - lag];
            if (b != 0) {
                out[i] = x[i] / b - 1;
            }
        }
        return out;
    }

    /** a ÷ b − 1. b 가 0 이거나 NaN 이면 NaN. */
    public static double[] ratioMinusOne(double[] a, double[] b) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) {
            if (b[i] != 0) {
                out[i] = a[i] / b[i] - 1;
            }
        }
        return out;
    }

    /** a ÷ b. b 가 0 이거나 NaN 이면 NaN. */
    public static double[] ratio(double[] a, double[] b) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) {
            if (b[i] != 0) {
                out[i] = a[i] / b[i];
            }
        }
        return out;
    }

    /** a − b. */
    public static double[] minus(double[] a, double[] b) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] - b[i];
        }
        return out;
    }

    /** a × k. */
    public static double[] scale(double[] a, double k) {
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i] * k;
        }
        return out;
    }

    /** 1 if a > b else 0 (같으면 0). 어느 쪽이든 NaN 이면 NaN. */
    public static double[] greater(double[] a, double[] b) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                out[i] = a[i] > b[i] ? 1 : 0;
            }
        }
        return out;
    }

    /** 1 if a > k else 0. */
    public static double[] above(double[] a, double k) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i])) {
                out[i] = a[i] > k ? 1 : 0;
            }
        }
        return out;
    }

    /** 1 if a < k else 0. */
    public static double[] below(double[] a, double k) {
        double[] out = nan(a.length);
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i])) {
                out[i] = a[i] < k ? 1 : 0;
            }
        }
        return out;
    }

    /** 0선 상향 교차: 직전 값 ≤ 0 이고 현재 값 > 0 이면 1, 아니면 0. 직전·현재 중 NaN 이 있으면 NaN. */
    public static double[] crossUp(double[] x) {
        double[] out = nan(x.length);
        for (int i = 1; i < x.length; i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(x[i - 1])) {
                out[i] = x[i - 1] <= 0 && x[i] > 0 ? 1 : 0;
            }
        }
        return out;
    }

    /** 0선 하향 교차: 직전 값 ≥ 0 이고 현재 값 < 0 이면 1, 아니면 0. */
    public static double[] crossDown(double[] x) {
        double[] out = nan(x.length);
        for (int i = 1; i < x.length; i++) {
            if (!Double.isNaN(x[i]) && !Double.isNaN(x[i - 1])) {
                out[i] = x[i - 1] >= 0 && x[i] < 0 ? 1 : 0;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 거래량 지표

    /** 상대 거래량: volume[t] ÷ (현재 봉을 뺀 직전 n개 평균 거래량). 분모가 0 이면 NaN. */
    public static double[] rvol(double[] volume, int n) {
        double[] prevAvg = sma(volume, n);
        double[] sums = volumeSum(volume, n);
        double[] out = nan(volume.length);
        for (int i = n; i < volume.length; i++) {
            if (sums[i - 1] > 0) {
                out[i] = volume[i] / prevAvg[i - 1];
            }
        }
        return out;
    }

    /** 거래량 오실레이터: 100 × (짧은 평균 − 긴 평균) ÷ 긴 평균. 긴 평균이 0 이면 NaN. */
    public static double[] volumeOscillator(double[] volume, int shortN, int longN) {
        double[] s = sma(volume, shortN);
        double[] l = sma(volume, longN);
        double[] lSum = volumeSum(volume, longN);
        double[] out = nan(volume.length);
        for (int i = 0; i < volume.length; i++) {
            if (!Double.isNaN(l[i]) && lSum[i] > 0 && !Double.isNaN(s[i])) {
                out[i] = 100 * (s[i] - l[i]) / l[i];
            }
        }
        return out;
    }

    /**
     * MFI: 대표가격 (H+L+C)/3 이 직전보다 크면 양의 흐름, 작으면 음의 흐름(같으면 둘 다 0).
     * 최근 n개 흐름으로 100 − 100/(1 + 양의 합/음의 합). 음의 합만 0 → 100, 양의 합만 0 → 0, 둘 다 0 → 50.
     */
    public static double[] mfi(double[] high, double[] low, double[] close, double[] volume, int n) {
        int len = close.length;
        double[] out = nan(len);
        double[] pos = new double[len];
        double[] neg = new double[len];
        for (int i = 1; i < len; i++) {
            double tp = (high[i] + low[i] + close[i]) / 3;
            double prev = (high[i - 1] + low[i - 1] + close[i - 1]) / 3;
            double flow = tp * volume[i];
            if (tp > prev) pos[i] = flow;
            else if (tp < prev) neg[i] = flow;
        }
        for (int i = n; i < len; i++) {
            double p = 0;
            double m = 0;
            for (int k = i - n + 1; k <= i; k++) {
                p += pos[k];
                m += neg[k];
            }
            if (p == 0 && m == 0) out[i] = 50;
            else if (m == 0) out[i] = 100;
            else if (p == 0) out[i] = 0;
            else out[i] = 100 - 100 / (1 + p / m);
        }
        return out;
    }

    /** CMF: 최근 n봉 (자금흐름 배수 × volume) 합 ÷ volume 합. high = low 면 배수 0, 거래량 합 0 이면 NaN. */
    public static double[] cmf(double[] high, double[] low, double[] close, double[] volume, int n) {
        int len = close.length;
        double[] mfv = new double[len];
        for (int i = 0; i < len; i++) {
            double range = high[i] - low[i];
            double mult = range == 0 ? 0 : ((close[i] - low[i]) - (high[i] - close[i])) / range;
            mfv[i] = mult * volume[i];
        }
        double[] vSum = volumeSum(volume, n);
        double[] out = nan(len);
        double sum = 0;
        for (int i = 0; i < len; i++) {
            sum += mfv[i];
            if (i >= n) sum -= mfv[i - n];
            if (i >= n - 1 && vSum[i] > 0) {
                out[i] = sum / vSum[i];
            }
        }
        return out;
    }

    /** OBV: 종가 상승이면 +volume, 하락이면 −volume, 같으면 유지. 구간 첫 봉은 0. */
    public static double[] obv(double[] close, double[] volume) {
        int len = close.length;
        double[] out = new double[len];
        for (int i = 1; i < len; i++) {
            if (close[i] > close[i - 1]) out[i] = out[i - 1] + volume[i];
            else if (close[i] < close[i - 1]) out[i] = out[i - 1] - volume[i];
            else out[i] = out[i - 1];
        }
        return out;
    }

    // ---------------------------------------------------------------- RSI

    /**
     * RSI (와일더 평활). 첫 평균 상승·하락폭은 처음 n개 변화량의 산술평균, 이후 ((n−1)×이전 + 현재)/n.
     * avg_loss = 0 · avg_gain > 0 → 100, avg_gain = 0 · avg_loss > 0 → 0, 둘 다 0 → 50. 종가 n+1개가 모여야 첫 값.
     */
    public static double[] rsi(double[] close, int n) {
        int len = close.length;
        double[] out = nan(len);
        if (len <= n) {
            return out;
        }
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = close[i] - close[i - 1];
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        gain /= n;
        loss /= n;
        out[n] = rsiValue(gain, loss);
        for (int i = n + 1; i < len; i++) {
            double d = close[i] - close[i - 1];
            gain = ((n - 1) * gain + Math.max(d, 0)) / n;
            loss = ((n - 1) * loss + Math.max(-d, 0)) / n;
            out[i] = rsiValue(gain, loss);
        }
        return out;
    }

    private static double rsiValue(double gain, double loss) {
        if (gain == 0 && loss == 0) return 50;
        if (loss == 0) return 100;
        if (gain == 0) return 0;
        return 100 - 100 / (1 + gain / loss);
    }

    /** StochRSI: 100 × (RSI − 최근 r개 RSI 최솟값) ÷ (최댓값 − 최솟값). 분모 0 이거나 유효 RSI 가 r개 미만이면 NaN. */
    public static double[] stochRsi(double[] rsi, int r) {
        int len = rsi.length;
        double[] out = nan(len);
        for (int i = r - 1; i < len; i++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            boolean ok = true;
            for (int k = i - r + 1; k <= i; k++) {
                double v = rsi[k];
                if (Double.isNaN(v)) {
                    ok = false;
                    break;
                }
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (ok && max > min) {
                out[i] = 100 * (rsi[i] - min) / (max - min);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- ATR

    /** TR = max(H−L, |H−이전 C|, |L−이전 C|). 구간 첫 봉은 이전 종가가 없어 H−L. */
    public static double[] trueRange(double[] high, double[] low, double[] close) {
        int len = close.length;
        double[] out = new double[len];
        for (int i = 0; i < len; i++) {
            double hl = high[i] - low[i];
            if (i == 0) {
                out[i] = hl;
            } else {
                double pc = close[i - 1];
                out[i] = Math.max(hl, Math.max(Math.abs(high[i] - pc), Math.abs(low[i] - pc)));
            }
        }
        return out;
    }

    /** ATR (와일더 평활): 첫 값은 처음 n개 TR 의 평균, 이후 ((n−1)×이전 + TR)/n. */
    public static double[] atr(double[] tr, int n) {
        int len = tr.length;
        double[] out = nan(len);
        if (len < n) {
            return out;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += tr[i];
        }
        double prev = sum / n;
        out[n - 1] = prev;
        for (int i = n; i < len; i++) {
            prev = ((n - 1) * prev + tr[i]) / n;
            out[i] = prev;
        }
        return out;
    }

    // ---------------------------------------------------------------- 볼린저 밴드

    /**
     * 최근 n개 종가의 모집단 표준편차(ddof = 0). 창 안 값이 모두 같으면 정확히 0.
     * 큰 가격(원화 BTC)에서 E[x²]−E[x]² 빼기 오차가 커서 창마다 평균을 먼저 구하는 두 번 계산을 쓴다.
     */
    public static double[] stdev(double[] x, int n) {
        int len = x.length;
        double[] out = nan(len);
        for (int i = n - 1; i < len; i++) {
            double sum = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int k = i - n + 1; k <= i; k++) {
                sum += x[k];
                min = Math.min(min, x[k]);
                max = Math.max(max, x[k]);
            }
            if (min == max) {
                out[i] = 0;
                continue;
            }
            double mean = sum / n;
            double sq = 0;
            for (int k = i - n + 1; k <= i; k++) {
                double d = x[k] - mean;
                sq += d * d;
            }
            out[i] = Math.sqrt(sq / n);
        }
        return out;
    }
}
