package com.mch.strategy.training;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 예측을 매매로 바꿔 수수료를 뺀 성과를 계산한다 (자산마다 따로 거래, 자금은 자산 수로 똑같이 나눔).
 * <ul>
 *   <li>신호: 확률 방식이면 상승 확률 ≥ 기준(그리고 하락 확률보다 큼)일 때 롱, 하락 확률 ≥ 기준이면 숏.
 *       클래스 방식이면 예측이 상승이면 롱, 하락이면 숏.</li>
 *   <li>진입 · 청산은 판단 봉 종가에 한다고 가정한다. 매번 수수료 + 슬리피지를 한쪽 비용으로 뺀다.</li>
 *   <li>보유: holdBars 봉이 지나면 청산. 같은 방향 신호가 또 나오면 보유를 연장(선택), 반대 신호면 뒤집는다.</li>
 *   <li>숏은 참고용이다. 업비트 원화 현물에서는 공매도를 할 수 없다.</li>
 * </ul>
 * 모든 % 값은 퍼센트 단위(1.5 = 1.5%)다.
 */
public final class Backtester {

    /** 업비트 원화 마켓 기본 거래 수수료(한쪽) · 기본 슬리피지 가정(한쪽), % */
    public static final double DEFAULT_FEE_PCT = 0.05;
    public static final double DEFAULT_SLIPPAGE_PCT = 0.02;

    private static final double[] SWEEP = {0.34, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.8};
    private static final int MAX_CURVE_POINTS = 1500;
    private static final int MAX_TRADES = 300;

    private Backtester() {
    }

    public record Settings(double feePct, double slippagePct, boolean useProbability, double upThreshold,
                           double downThreshold, int holdBars, boolean extendOnSignal, boolean allowShort) {

        /** 학습 직후 자동으로 계산하는 기본 규칙: 예측 클래스대로 롱 · 숏, 예측 기간만큼 보유 */
        public static Settings defaults(double feePct, double slippagePct, int holdBars) {
            return new Settings(feePct, slippagePct, false, 0.5, 0.5, holdBars, true, true);
        }

        double costRate() {
            return (feePct + slippagePct) / 100;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feePct", feePct);
            m.put("slippagePct", slippagePct);
            m.put("useProbability", useProbability);
            m.put("upThreshold", upThreshold);
            m.put("downThreshold", downThreshold);
            m.put("holdBars", holdBars);
            m.put("extendOnSignal", extendOnSignal);
            m.put("allowShort", allowShort);
            return m;
        }
    }

    /** 행 순서는 자산별로 모은 뒤 시각순이어야 한다 (학습 데이터 파일 순서) */
    public record Input(long[] time, int[] asset, float[] close, int[] pred, double[][] proba,
                        List<String> assets, long barMs) {
    }

    /** 목록 저장용 요약 */
    public record Summary(double netReturnPct, double maxDrawdownPct, int trades) {
    }

    private record Trade(int asset, int side, long entryTime, long exitTime, double entry, double exit,
                        int bars, double grossPct, double netPct, String reason) {
    }

    private record AssetRun(List<Trade> trades, long[] curveTime, double[] curveNet, double[] curveGross,
                            long inPositionRows, long rows, double buyHoldPct) {
    }

    public static Summary summary(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) result.get("total");
        return new Summary(((Number) t.get("netReturnPct")).doubleValue(), ((Number) t.get("maxDrawdownPct")).doubleValue(),
                ((Number) t.get("trades")).intValue());
    }

    public static Map<String, Object> run(Input in, Settings s, boolean details) {
        int assets = in.assets().size();
        List<AssetRun> runs = new ArrayList<>();
        for (int a = 0; a < assets; a++) {
            runs.add(simulate(in, s, a));
        }

        // 자산별 곡선을 시각 기준으로 합친다 (각 자산 1/A 의 자금, 아직 시작 전이면 1 로 둔다)
        TreeMap<Long, double[]> merged = new TreeMap<>();
        for (int a = 0; a < assets; a++) {
            AssetRun r = runs.get(a);
            for (int k = 0; k < r.curveTime.length; k++) {
                double[] v = merged.computeIfAbsent(r.curveTime[k], key -> new double[2 * assets]);
                v[a] = r.curveNet[k];
                v[assets + a] = r.curveGross[k];
            }
        }
        double[] lastNet = new double[assets], lastGross = new double[assets];
        java.util.Arrays.fill(lastNet, 1);
        java.util.Arrays.fill(lastGross, 1);
        int n = merged.size();
        long[] time = new long[n];
        double[] net = new double[n], gross = new double[n];
        int k = 0;
        int activeAssets = (int) runs.stream().filter(r -> r.rows > 0).count();
        for (Map.Entry<Long, double[]> e : merged.entrySet()) {
            double sumN = 0, sumG = 0;
            for (int a = 0; a < assets; a++) {
                if (e.getValue()[a] != 0) {
                    lastNet[a] = e.getValue()[a];
                    lastGross[a] = e.getValue()[assets + a];
                }
                if (runs.get(a).rows > 0) {
                    sumN += lastNet[a];
                    sumG += lastGross[a];
                }
            }
            time[k] = e.getKey();
            net[k] = activeAssets == 0 ? 1 : sumN / activeAssets;
            gross[k] = activeAssets == 0 ? 1 : sumG / activeAssets;
            k++;
        }

        List<Trade> allTrades = new ArrayList<>();
        runs.forEach(r -> allTrades.addAll(r.trades));
        allTrades.sort((x, y) -> Long.compare(x.entryTime, y.entryTime));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", s.toMap());
        Map<String, Object> total = stats(allTrades, net, gross, time,
                runs.stream().mapToLong(r -> r.inPositionRows).sum(), runs.stream().mapToLong(r -> r.rows).sum(),
                runs.stream().filter(r -> r.rows > 0).mapToDouble(r -> r.buyHoldPct).average().orElse(0));
        out.put("total", total);
        if (!details) {
            return out;
        }
        Map<String, Object> byAsset = new LinkedHashMap<>();
        for (int a = 0; a < assets; a++) {
            AssetRun r = runs.get(a);
            if (r.rows == 0) {
                continue;
            }
            byAsset.put(in.assets().get(a), stats(r.trades, r.curveNet, r.curveGross, r.curveTime, r.inPositionRows, r.rows, r.buyHoldPct));
        }
        out.put("byAsset", byAsset);
        out.put("curve", curve(time, net, gross, runs, in.assets()));
        out.put("monthly", periodReturns(time, net, true));
        List<Map<String, Object>> trades = new ArrayList<>();
        int from = Math.max(0, allTrades.size() - MAX_TRADES);
        for (Trade t : allTrades.subList(from, allTrades.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("asset", in.assets().get(t.asset));
            m.put("side", t.side > 0 ? "롱" : "숏");
            m.put("entryTime", t.entryTime);
            m.put("exitTime", t.exitTime);
            m.put("entry", t.entry);
            m.put("exit", t.exit);
            m.put("bars", t.bars);
            m.put("grossPct", t.grossPct);
            m.put("netPct", t.netPct);
            m.put("reason", t.reason);
            trades.add(m);
        }
        out.put("trades", trades);
        out.put("tradesShown", trades.size());
        if (in.proba() != null) {
            List<Map<String, Object>> sweep = new ArrayList<>();
            for (double th : SWEEP) {
                Settings ss = new Settings(s.feePct, s.slippagePct, true, th, th, s.holdBars, s.extendOnSignal, s.allowShort);
                @SuppressWarnings("unchecked")
                Map<String, Object> t = (Map<String, Object>) run(in, ss, false).get("total");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("threshold", th);
                m.put("netReturnPct", t.get("netReturnPct"));
                m.put("grossReturnPct", t.get("grossReturnPct"));
                m.put("maxDrawdownPct", t.get("maxDrawdownPct"));
                m.put("trades", t.get("trades"));
                m.put("winRate", t.get("winRate"));
                sweep.add(m);
            }
            out.put("sweep", sweep);
        }
        return out;
    }

    private static AssetRun simulate(Input in, Settings s, int asset) {
        double cost = s.costRate();
        List<Trade> trades = new ArrayList<>();
        List<Long> ct = new ArrayList<>();
        List<double[]> cv = new ArrayList<>();
        double eqNet = 1, eqGross = 1;
        int pos = 0;
        double entry = 0;
        long entryTime = 0, due = 0;
        int entryRow = 0, rowIndex = 0;
        long inPos = 0, rows = 0;
        double firstPrice = Double.NaN, lastPrice = Double.NaN;
        long lastTime = 0;

        for (int i = 0; i < in.time().length; i++) {
            if (in.asset()[i] != asset) {
                continue;
            }
            double c = in.close()[i];
            if (!(c > 0)) {
                continue;
            }
            long t = in.time()[i];
            rows++;
            rowIndex++;
            if (Double.isNaN(firstPrice)) {
                firstPrice = c;
            }
            lastPrice = c;
            lastTime = t;
            int sig = signal(in, s, i);

            if (pos != 0) {
                boolean flip = sig == -pos;
                boolean timeUp = t >= due;
                if (sig == pos && s.extendOnSignal) {
                    due = t + s.holdBars * in.barMs();
                } else if (flip || timeUp) {
                    double g = pos * (c / entry - 1);
                    eqGross *= 1 + g;
                    eqNet *= (1 + g) * (1 - cost);
                    trades.add(new Trade(asset, pos, entryTime, t, entry, c, rowIndex - entryRow, g * 100,
                            ((1 - cost) * (1 + g) * (1 - cost) - 1) * 100, flip ? "반대 신호" : "보유 기간 끝"));
                    pos = 0;
                }
            }
            if (pos == 0 && sig != 0) {
                pos = sig;
                entry = c;
                entryTime = t;
                entryRow = rowIndex;
                due = t + s.holdBars * in.barMs();
                eqNet *= 1 - cost;
            }
            if (pos != 0) {
                inPos++;
            }
            double open = pos == 0 ? 1 : 1 + pos * (c / entry - 1);
            ct.add(t);
            cv.add(new double[]{eqNet * open, eqGross * open});
        }
        if (pos != 0) {
            double g = pos * (lastPrice / entry - 1);
            eqGross *= 1 + g;
            eqNet *= (1 + g) * (1 - cost);
            trades.add(new Trade(asset, pos, entryTime, lastTime, entry, lastPrice, rowIndex - entryRow, g * 100,
                    ((1 - cost) * (1 + g) * (1 - cost) - 1) * 100, "구간 끝"));
            if (!cv.isEmpty()) {
                cv.set(cv.size() - 1, new double[]{eqNet, eqGross});
            }
        }
        long[] times = ct.stream().mapToLong(Long::longValue).toArray();
        double[] net = new double[cv.size()], gross = new double[cv.size()];
        for (int k = 0; k < cv.size(); k++) {
            net[k] = cv.get(k)[0];
            gross[k] = cv.get(k)[1];
        }
        double bh = Double.isNaN(firstPrice) ? 0 : (lastPrice / firstPrice - 1) * 100;
        return new AssetRun(trades, times, net, gross, inPos, rows, bh);
    }

    /** 1 = 롱, −1 = 숏, 0 = 신호 없음 */
    static int signal(Input in, Settings s, int i) {
        int sig;
        if (s.useProbability && in.proba() != null) {
            double[] p = in.proba()[i];
            if (p[2] >= s.upThreshold && p[2] >= p[0]) {
                sig = 1;
            } else if (p[0] >= s.downThreshold && p[0] > p[2]) {
                sig = -1;
            } else {
                sig = 0;
            }
        } else {
            sig = in.pred()[i] == 2 ? 1 : in.pred()[i] == 0 ? -1 : 0;
        }
        return sig < 0 && !s.allowShort ? 0 : sig;
    }

    private static Map<String, Object> stats(List<Trade> trades, double[] net, double[] gross, long[] time,
                                             long inPos, long rows, double buyHoldPct) {
        Map<String, Object> m = new LinkedHashMap<>();
        double end = net.length == 0 ? 1 : net[net.length - 1];
        double endGross = gross.length == 0 ? 1 : gross[gross.length - 1];
        double peak = 1, mdd = 0;
        for (double v : net) {
            peak = Math.max(peak, v);
            mdd = Math.max(mdd, (peak - v) / peak);
        }
        long wins = trades.stream().filter(t -> t.netPct > 0).count();
        long longs = trades.stream().filter(t -> t.side > 0).count();
        m.put("netReturnPct", (end - 1) * 100);
        m.put("grossReturnPct", (endGross - 1) * 100);
        m.put("buyHoldPct", buyHoldPct);
        m.put("maxDrawdownPct", mdd * 100);
        m.put("trades", trades.size());
        m.put("longTrades", longs);
        m.put("shortTrades", trades.size() - longs);
        m.put("winRate", trades.isEmpty() ? null : (double) wins / trades.size());
        m.put("avgTradeNetPct", trades.isEmpty() ? null : trades.stream().mapToDouble(Trade::netPct).average().orElse(0));
        m.put("avgHoldBars", trades.isEmpty() ? null : trades.stream().mapToInt(Trade::bars).average().orElse(0));
        m.put("exposure", rows == 0 ? null : (double) inPos / rows);
        m.put("longNetPct", trades.stream().filter(t -> t.side > 0).mapToDouble(Trade::netPct).sum());
        m.put("shortNetPct", trades.stream().filter(t -> t.side < 0).mapToDouble(Trade::netPct).sum());
        List<Map<String, Object>> daily = periodReturns(time, net, false);
        double[] dr = daily.stream().mapToDouble(d -> ((Number) d.get("returnPct")).doubleValue()).toArray();
        if (dr.length >= 5) {
            double mean = java.util.Arrays.stream(dr).average().orElse(0);
            double sd = Math.sqrt(java.util.Arrays.stream(dr).map(x -> (x - mean) * (x - mean)).sum() / (dr.length - 1));
            m.put("sharpe", sd == 0 ? null : mean / sd * Math.sqrt(365));
        } else {
            m.put("sharpe", null);
        }
        m.put("days", dr.length);
        return m;
    }

    /** 하루(UTC) 또는 한 달 단위 수익률 — 기간 마지막 값 기준 */
    private static List<Map<String, Object>> periodReturns(long[] time, double[] equity, boolean monthly) {
        TreeMap<String, Double> last = new TreeMap<>();
        for (int k = 0; k < time.length; k++) {
            String key = LocalDate.ofInstant(Instant.ofEpochMilli(time[k]), ZoneOffset.UTC).toString();
            last.put(monthly ? key.substring(0, 7) : key, equity[k]);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        double prev = 1;
        for (Map.Entry<String, Double> e : last.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", e.getKey());
            m.put("returnPct", (e.getValue() / prev - 1) * 100);
            out.add(m);
            prev = e.getValue();
        }
        return out;
    }

    /** 화면용 곡선 (점 최대 1500개): 시각 · 순자산 · 비용 전 · 자산별 단순 보유 */
    private static Map<String, Object> curve(long[] time, double[] net, double[] gross, List<AssetRun> runs, List<String> assets) {
        int step = Math.max(1, time.length / MAX_CURVE_POINTS);
        List<Long> t = new ArrayList<>();
        List<Double> n = new ArrayList<>(), g = new ArrayList<>(), dd = new ArrayList<>();
        double peak = 1;
        for (int k = 0; k < time.length; k++) {
            peak = Math.max(peak, net[k]);
            if (k % step == 0 || k == time.length - 1) {
                t.add(time[k]);
                n.add((net[k] - 1) * 100);
                g.add((gross[k] - 1) * 100);
                dd.add(-(peak - net[k]) / peak * 100);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", t);
        out.put("netPct", n);
        out.put("grossPct", g);
        out.put("drawdownPct", dd);
        return out;
    }
}
