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
 *   <li>신호 (+1 상승 · −1 하락 · 0 없음):
 *       클래스 방식 = 예측 클래스 그대로. 확률 방식 = 상승 확률 ≥ 기준이면 +1, 하락 확률 ≥ 기준이면 −1.
 *       기대 수익 방식 = (상승 확률 − 하락 확률) × 그 행의 정답 기준(%) 이 왕복 비용 + 여유보다 크면 +1, −(왕복 비용 + 여유)보다 작으면 −1.</li>
 *   <li>롱만: 상승 신호에 사고, 하락 신호 · 보유 기간 끝에 판다 (업비트 원화 현물은 공매도 불가).
 *       allowShort 는 테스트 · 참고용으로만 남겨 둔다.</li>
 *   <li>시장가: 진입 · 청산은 판단 봉 종가에 바로 체결된다고 가정한다. 매번 수수료 + 슬리피지를 한쪽 비용으로 뺀다.</li>
 *   <li>지정가: 판단 봉 종가에 주문을 걸고, 다음 W봉 안에 가격이 그 값을 넘어서야(매수는 저가 &lt; 주문가,
 *       매도는 고가 &gt; 주문가) 주문가에 체결된다. 닿기만 한 경우는 대기 순번 때문에 체결되지 않았다고 본다.
 *       비용은 수수료만 뺀다. 진입이 W봉 안에 안 되면 취소(놓친 거래), 청산이 안 되면 W봉째 종가에 시장가로 판다.</li>
 *   <li>보유: holdBars 봉이 지나면 청산. 같은 방향 신호가 또 나오면 보유를 연장(선택), 반대 신호면 청산한다.</li>
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

    public static final String MARKET = "MARKET";
    public static final String LIMIT = "LIMIT";
    public static final int DEFAULT_LIMIT_WAIT_BARS = 1;

    /** 진입 규칙 */
    public static final String RULE_CLASS = "CLASS";
    public static final String RULE_PROBA = "PROBA";
    public static final String RULE_EV = "EV";

    /**
     * @param rule          CLASS(예측 클래스) · PROBA(확률 기준) · EV(기대 수익 &gt; 비용). useProbability 는 rule 이 PROBA 인지와 같다.
     * @param evMarginPct   기대 수익 규칙에서 왕복 비용 위에 더 요구할 여유(%)
     * @param orderType     MARKET(시장가) · LIMIT(지정가)
     * @param limitWaitBars 지정가 주문을 몇 봉까지 기다릴지 (시장가면 쓰지 않음)
     */
    public record Settings(double feePct, double slippagePct, boolean useProbability, double upThreshold,
                           double downThreshold, int holdBars, boolean extendOnSignal, boolean allowShort,
                           String orderType, int limitWaitBars, String rule, double evMarginPct) {

        public Settings(double feePct, double slippagePct, boolean useProbability, double upThreshold,
                        double downThreshold, int holdBars, boolean extendOnSignal, boolean allowShort,
                        String orderType, int limitWaitBars) {
            this(feePct, slippagePct, useProbability, upThreshold, downThreshold, holdBars, extendOnSignal, allowShort,
                    orderType, limitWaitBars, useProbability ? RULE_PROBA : RULE_CLASS, 0);
        }

        /** 시장가 */
        public Settings(double feePct, double slippagePct, boolean useProbability, double upThreshold,
                        double downThreshold, int holdBars, boolean extendOnSignal, boolean allowShort) {
            this(feePct, slippagePct, useProbability, upThreshold, downThreshold, holdBars, extendOnSignal, allowShort,
                    MARKET, DEFAULT_LIMIT_WAIT_BARS);
        }

        /**
         * 학습 직후 자동으로 계산하는 기본 규칙: 롱만, 예측 기간만큼 보유, 시장가.
         * 확률을 내는 모델이면 기대 수익 &gt; 왕복 비용일 때만 사고, 아니면 예측 클래스대로.
         */
        public static Settings defaults(double feePct, double slippagePct, int holdBars, boolean probability) {
            return new Settings(feePct, slippagePct, false, 0.5, 0.5, holdBars, true, false,
                    MARKET, DEFAULT_LIMIT_WAIT_BARS, probability ? RULE_EV : RULE_CLASS, 0);
        }

        public static Settings defaults(double feePct, double slippagePct, int holdBars) {
            return defaults(feePct, slippagePct, holdBars, false);
        }

        public boolean isLimit() {
            return LIMIT.equals(orderType);
        }

        /** 왕복 비용(%): 지정가면 수수료 × 2, 시장가면 (수수료 + 슬리피지) × 2 */
        double roundTripPct() {
            return isLimit() ? feePct * 2 : (feePct + slippagePct) * 2;
        }

        /** 시장가 한쪽 비용 (수수료 + 슬리피지) */
        double marketCostRate() {
            return (feePct + slippagePct) / 100;
        }

        /** 지정가 한쪽 비용 (수수료만) */
        double limitCostRate() {
            return feePct / 100;
        }

        Settings withOrder(String type, double slippage) {
            return new Settings(feePct, slippage, useProbability, upThreshold, downThreshold, holdBars, extendOnSignal,
                    allowShort, type, limitWaitBars, rule, evMarginPct);
        }

        Settings withThreshold(double th) {
            return new Settings(feePct, slippagePct, true, th, th, holdBars, extendOnSignal, allowShort, orderType,
                    limitWaitBars, RULE_PROBA, evMarginPct);
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
            m.put("orderType", orderType);
            m.put("limitWaitBars", limitWaitBars);
            m.put("rule", rule);
            m.put("evMarginPct", evMarginPct);
            return m;
        }
    }

    /**
     * 행 순서는 자산별로 모은 뒤 시각순이어야 한다 (학습 데이터 파일 순서).
     * high · low 는 지정가 체결 판단용이며, 예전 학습 데이터면 null 이다.
     * thresholdPct 는 행마다의 정답 기준(%) — 기대 수익 규칙에 쓴다 (없으면 null).
     */
    public record Input(long[] time, int[] asset, float[] close, float[] high, float[] low, int[] pred,
                        double[][] proba, List<String> assets, long barMs, float[] thresholdPct) {

        public Input(long[] time, int[] asset, float[] close, float[] high, float[] low, int[] pred,
                     double[][] proba, List<String> assets, long barMs) {
            this(time, asset, close, high, low, pred, proba, assets, barMs, null);
        }

        public Input(long[] time, int[] asset, float[] close, int[] pred, double[][] proba, List<String> assets, long barMs) {
            this(time, asset, close, null, null, pred, proba, assets, barMs, null);
        }

        public boolean canUseExpectedValue() {
            return proba != null && thresholdPct != null;
        }

        public boolean hasRange() {
            return high != null && low != null;
        }
    }

    /** 목록 저장용 요약 */
    public record Summary(double netReturnPct, double maxDrawdownPct, int trades) {
    }

    private record Trade(int asset, int side, long entryTime, long exitTime, double entry, double exit,
                        int bars, double grossPct, double netPct, String reason, String entryOrder, String exitOrder) {
    }

    /** 지정가 주문 집계. 시장가면 모두 0 */
    private record OrderStats(int entryOrders, int entryFilled, int entryMissed, int exitOrders, int exitFilled, int exitFallback) {
        static final OrderStats NONE = new OrderStats(0, 0, 0, 0, 0, 0);

        OrderStats plus(OrderStats o) {
            return new OrderStats(entryOrders + o.entryOrders, entryFilled + o.entryFilled, entryMissed + o.entryMissed,
                    exitOrders + o.exitOrders, exitFilled + o.exitFilled, exitFallback + o.exitFallback);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryOrders", entryOrders);
            m.put("entryFilled", entryFilled);
            m.put("entryMissed", entryMissed);
            m.put("entryFillRate", entryOrders == 0 ? null : (double) entryFilled / entryOrders);
            m.put("exitOrders", exitOrders);
            m.put("exitFilled", exitFilled);
            m.put("exitFallback", exitFallback);
            m.put("exitFillRate", exitOrders == 0 ? null : (double) exitFilled / exitOrders);
            return m;
        }
    }

    private record AssetRun(List<Trade> trades, long[] curveTime, double[] curveNet, double[] curveGross,
                            long inPositionRows, long rows, double buyHoldPct, OrderStats orders) {
    }

    /** 자산별 결과를 시각 기준으로 합친 것 */
    private record Combined(List<AssetRun> runs, long[] time, double[] net, double[] gross) {
    }

    public static Summary summary(Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) result.get("total");
        return new Summary(((Number) t.get("netReturnPct")).doubleValue(), ((Number) t.get("maxDrawdownPct")).doubleValue(),
                ((Number) t.get("trades")).intValue());
    }

    public static Map<String, Object> run(Input in, Settings s, boolean details) {
        if (s.isLimit() && !in.hasRange()) {
            throw new IllegalStateException("이 학습 데이터에는 봉의 고가 · 저가가 저장되어 있지 않아 지정가 체결을 계산할 수 없습니다. "
                    + "학습 데이터를 「같은 설정으로 새로 만들기」로 다시 만든 뒤 실험을 다시 실행해 주세요.");
        }
        Combined c = combine(in, s);
        List<AssetRun> runs = c.runs();
        long[] time = c.time();
        double[] net = c.net(), gross = c.gross();
        int assets = in.assets().size();

        List<Trade> allTrades = new ArrayList<>();
        runs.forEach(r -> allTrades.addAll(r.trades));
        allTrades.sort((x, y) -> Long.compare(x.entryTime, y.entryTime));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", s.toMap());
        Map<String, Object> total = stats(allTrades, net, gross, time,
                runs.stream().mapToLong(r -> r.inPositionRows).sum(), runs.stream().mapToLong(r -> r.rows).sum(),
                runs.stream().filter(r -> r.rows > 0).mapToDouble(r -> r.buyHoldPct).average().orElse(0));
        if (s.isLimit()) {
            total.put("orders", runs.stream().map(AssetRun::orders).reduce(OrderStats.NONE, OrderStats::plus).toMap());
        }
        out.put("total", total);
        out.put("limitAvailable", in.hasRange());
        if (!details) {
            return out;
        }
        Map<String, Object> byAsset = new LinkedHashMap<>();
        for (int a = 0; a < assets; a++) {
            AssetRun r = runs.get(a);
            if (r.rows == 0) {
                continue;
            }
            Map<String, Object> st = stats(r.trades, r.curveNet, r.curveGross, r.curveTime, r.inPositionRows, r.rows, r.buyHoldPct);
            if (s.isLimit()) {
                st.put("orders", r.orders.toMap());
            }
            byAsset.put(in.assets().get(a), st);
        }
        out.put("byAsset", byAsset);
        out.put("curve", curve(time, net, gross));
        out.put("compare", compare(in, s, time.length));
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
            m.put("entryOrder", t.entryOrder);
            m.put("exitOrder", t.exitOrder);
            trades.add(m);
        }
        out.put("trades", trades);
        out.put("tradesShown", trades.size());
        if (in.proba() != null) {
            List<Map<String, Object>> sweep = new ArrayList<>();
            for (double th : SWEEP) {
                @SuppressWarnings("unchecked")
                Map<String, Object> t = (Map<String, Object>) run(in, s.withThreshold(th), false).get("total");
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

    /**
     * 같은 신호 · 보유 규칙으로 주문 방식만 바꿔 본 비교: 시장가(슬리피지 포함) · 시장가(슬리피지 0) · 지정가.
     * 곡선은 본 결과의 곡선과 같은 시각에 찍는다 (모든 방식이 같은 행마다 점을 남기므로 시각이 같다).
     */
    private static List<Map<String, Object>> compare(Input in, Settings s, int points) {
        List<Object[]> cases = new ArrayList<>();
        cases.add(new Object[]{"market", "시장가 · 슬리피지 " + fmtPct(s.slippagePct()), s.withOrder(MARKET, s.slippagePct())});
        if (s.slippagePct() > 0) {
            cases.add(new Object[]{"marketNoSlip", "시장가 · 슬리피지 0", s.withOrder(MARKET, 0)});
        }
        if (in.hasRange()) {
            cases.add(new Object[]{"limit", "지정가 · " + s.limitWaitBars() + "봉 대기", s.withOrder(LIMIT, s.slippagePct())});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int[] idx = sampleIndices(points);
        for (Object[] cs : cases) {
            Settings ss = (Settings) cs[2];
            Combined c = combine(in, ss);
            List<Trade> trades = new ArrayList<>();
            c.runs().forEach(r -> trades.addAll(r.trades));
            Map<String, Object> st = stats(trades, c.net(), c.gross(), c.time(),
                    c.runs().stream().mapToLong(r -> r.inPositionRows).sum(), c.runs().stream().mapToLong(r -> r.rows).sum(), 0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", cs[0]);
            m.put("label", cs[1]);
            m.put("selected", ss.isLimit() == s.isLimit() && ss.slippagePct() == s.slippagePct());
            m.put("netReturnPct", st.get("netReturnPct"));
            m.put("grossReturnPct", st.get("grossReturnPct"));
            m.put("maxDrawdownPct", st.get("maxDrawdownPct"));
            m.put("trades", st.get("trades"));
            m.put("winRate", st.get("winRate"));
            m.put("avgTradeNetPct", st.get("avgTradeNetPct"));
            double roundTrip = ss.isLimit() ? ss.feePct() * 2 : (ss.feePct() + ss.slippagePct()) * 2;
            m.put("roundTripCostPct", roundTrip);
            if (ss.isLimit()) {
                m.put("orders", c.runs().stream().map(AssetRun::orders).reduce(OrderStats.NONE, OrderStats::plus).toMap());
            }
            List<Double> curve = new ArrayList<>(idx.length);
            for (int k : idx) {
                curve.add(k < c.net().length ? (c.net()[k] - 1) * 100 : null);
            }
            m.put("netPct", curve);
            out.add(m);
        }
        return out;
    }

    private static Combined combine(Input in, Settings s) {
        int assets = in.assets().size();
        List<AssetRun> runs = new ArrayList<>();
        for (int a = 0; a < assets; a++) {
            runs.add(s.isLimit() ? simulateLimit(in, s, a) : simulate(in, s, a));
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
        return new Combined(runs, time, net, gross);
    }

    /** 시장가: 판단 봉 종가에 바로 체결 */
    private static AssetRun simulate(Input in, Settings s, int asset) {
        double cost = s.marketCostRate();
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
                            ((1 - cost) * (1 + g) * (1 - cost) - 1) * 100, flip ? (pos > 0 ? "하락 신호" : "상승 신호") : "보유 기간 끝", "시장가", "시장가"));
                    pos = 0;
                }
            }
            if (pos == 0 && canEnter(s, sig)) {
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
                    ((1 - cost) * (1 + g) * (1 - cost) - 1) * 100, "구간 끝", "시장가", "시장가"));
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
        return new AssetRun(trades, times, net, gross, inPos, rows, bh, OrderStats.NONE);
    }

    private static final int FLAT = 0, WAIT_ENTRY = 1, HOLD = 2, WAIT_EXIT = 3;

    /**
     * 지정가: 판단 봉 종가에 주문을 걸고 다음 봉부터 W봉 동안 체결을 기다린다.
     * 한 행(봉)마다 ① 걸어 둔 주문이 이 봉 안에서 체결됐는지 → ② 봉이 끝난 시각의 판단(청산 · 진입 주문) 순서로 본다.
     */
    private static AssetRun simulateLimit(Input in, Settings s, int asset) {
        double limitCost = s.limitCostRate(), marketCost = s.marketCostRate();
        long wait = s.limitWaitBars() * in.barMs();
        long hold = s.holdBars * in.barMs();
        List<Trade> trades = new ArrayList<>();
        List<Long> ct = new ArrayList<>();
        List<double[]> cv = new ArrayList<>();
        double eqNet = 1, eqGross = 1;
        int state = FLAT, side = 0;
        double orderPrice = 0, entry = 0, entryCost = 0;
        long orderTime = 0, entryTime = 0, due = 0;
        String exitReason = "";
        int entryRow = 0, rowIndex = 0;
        long inPos = 0, rows = 0;
        double firstPrice = Double.NaN, lastPrice = Double.NaN;
        long lastTime = 0;
        int entryOrders = 0, entryFilled = 0, entryMissed = 0, exitOrders = 0, exitFilled = 0, exitFallback = 0;

        for (int i = 0; i < in.time().length; i++) {
            if (in.asset()[i] != asset) {
                continue;
            }
            double c = in.close()[i];
            if (!(c > 0)) {
                continue;
            }
            double hi = Float.isNaN(in.high()[i]) ? c : in.high()[i];
            double lo = Float.isNaN(in.low()[i]) ? c : in.low()[i];
            long t = in.time()[i];
            rows++;
            rowIndex++;
            if (Double.isNaN(firstPrice)) {
                firstPrice = c;
            }
            lastPrice = c;
            lastTime = t;

            // ① 걸어 둔 주문
            if (state == WAIT_ENTRY) {
                boolean inWindow = t <= orderTime + wait;
                boolean crossed = side > 0 ? lo < orderPrice : hi > orderPrice;
                if (inWindow && crossed) {
                    state = HOLD;
                    entry = orderPrice;
                    entryCost = limitCost;
                    entryTime = t;
                    entryRow = rowIndex;
                    due = t + hold;
                    eqNet *= 1 - limitCost;
                    entryFilled++;
                } else if (!inWindow || t >= orderTime + wait) {
                    state = FLAT;
                    entryMissed++;
                }
            } else if (state == WAIT_EXIT) {
                boolean inWindow = t <= orderTime + wait;
                boolean crossed = side > 0 ? hi > orderPrice : lo < orderPrice;
                if (inWindow && crossed) {
                    double g = side * (orderPrice / entry - 1);
                    eqGross *= 1 + g;
                    eqNet *= (1 + g) * (1 - limitCost);
                    trades.add(new Trade(asset, side, entryTime, t, entry, orderPrice, rowIndex - entryRow, g * 100,
                            ((1 - entryCost) * (1 + g) * (1 - limitCost) - 1) * 100, exitReason, "지정가", "지정가"));
                    state = FLAT;
                    exitFilled++;
                } else if (!inWindow || t >= orderTime + wait) {
                    // 기다려도 안 팔리면 이 봉 종가에 시장가로 판다 (슬리피지 포함)
                    double g = side * (c / entry - 1);
                    eqGross *= 1 + g;
                    eqNet *= (1 + g) * (1 - marketCost);
                    trades.add(new Trade(asset, side, entryTime, t, entry, c, rowIndex - entryRow, g * 100,
                            ((1 - entryCost) * (1 + g) * (1 - marketCost) - 1) * 100,
                            exitReason + " · 지정가 미체결", "지정가", "시장가"));
                    state = FLAT;
                    exitFallback++;
                }
            }

            // ② 봉이 끝난 시각의 판단
            int sig = signal(in, s, i);
            if (state == HOLD) {
                boolean flip = sig == -side;
                if (sig == side && s.extendOnSignal) {
                    due = t + hold;
                } else if (flip || t >= due) {
                    state = WAIT_EXIT;
                    orderPrice = c;
                    orderTime = t;
                    exitReason = flip ? (side > 0 ? "하락 신호" : "상승 신호") : "보유 기간 끝";
                    exitOrders++;
                }
            }
            if (state == FLAT && canEnter(s, sig)) {
                state = WAIT_ENTRY;
                side = sig;
                orderPrice = c;
                orderTime = t;
                entryOrders++;
            }

            boolean holding = state == HOLD || state == WAIT_EXIT;
            if (holding) {
                inPos++;
            }
            double open = holding ? 1 + side * (c / entry - 1) : 1;
            ct.add(t);
            cv.add(new double[]{eqNet * open, eqGross * open});
        }
        if (state == HOLD || state == WAIT_EXIT) {
            double g = side * (lastPrice / entry - 1);
            eqGross *= 1 + g;
            eqNet *= (1 + g) * (1 - marketCost);
            trades.add(new Trade(asset, side, entryTime, lastTime, entry, lastPrice, rowIndex - entryRow, g * 100,
                    ((1 - entryCost) * (1 + g) * (1 - marketCost) - 1) * 100, "구간 끝", "지정가", "시장가"));
            if (!cv.isEmpty()) {
                cv.set(cv.size() - 1, new double[]{eqNet, eqGross});
            }
        } else if (state == WAIT_ENTRY) {
            entryMissed++;
        }
        long[] times = ct.stream().mapToLong(Long::longValue).toArray();
        double[] net = new double[cv.size()], gross = new double[cv.size()];
        for (int k = 0; k < cv.size(); k++) {
            net[k] = cv.get(k)[0];
            gross[k] = cv.get(k)[1];
        }
        double bh = Double.isNaN(firstPrice) ? 0 : (lastPrice / firstPrice - 1) * 100;
        return new AssetRun(trades, times, net, gross, inPos, rows, bh,
                new OrderStats(entryOrders, entryFilled, entryMissed, exitOrders, exitFilled, exitFallback));
    }

    private static String fmtPct(double v) {
        String txt = String.format("%.3f", v).replaceAll("0+$", "");
        return (txt.endsWith(".") ? txt.substring(0, txt.length() - 1) : txt) + "%";
    }

    /** 1 = 롱, −1 = 숏, 0 = 신호 없음 */
    static int signal(Input in, Settings s, int i) {
        if (RULE_EV.equals(s.rule) && in.canUseExpectedValue()) {
            double[] p = in.proba()[i];
            double ev = (p[2] - p[0]) * in.thresholdPct()[i];
            double need = s.roundTripPct() + s.evMarginPct;
            return ev > need ? 1 : ev < -need ? -1 : 0;
        }
        if ((RULE_PROBA.equals(s.rule) || s.useProbability) && in.proba() != null) {
            double[] p = in.proba()[i];
            if (p[2] >= s.upThreshold && p[2] >= p[0]) {
                return 1;
            }
            if (p[0] >= s.downThreshold && p[0] > p[2]) {
                return -1;
            }
            return 0;
        }
        return in.pred()[i] == 2 ? 1 : in.pred()[i] == 0 ? -1 : 0;
    }

    /** 이 신호로 새로 들어갈 수 있는지. 롱만 할 때 하락 신호는 진입이 아니라 청산에만 쓴다. */
    private static boolean canEnter(Settings s, int sig) {
        return sig > 0 || (sig < 0 && s.allowShort);
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

    /** 화면 곡선에 찍을 행 번호 (점 최대 1500개, 마지막 점 포함) */
    private static int[] sampleIndices(int length) {
        int step = Math.max(1, length / MAX_CURVE_POINTS);
        List<Integer> idx = new ArrayList<>();
        for (int k = 0; k < length; k++) {
            if (k % step == 0 || k == length - 1) idx.add(k);
        }
        return idx.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 화면용 곡선: 시각 · 순자산 · 비용 전 · 낙폭 */
    private static Map<String, Object> curve(long[] time, double[] net, double[] gross) {
        List<Long> t = new ArrayList<>();
        List<Double> n = new ArrayList<>(), g = new ArrayList<>(), dd = new ArrayList<>();
        double[] peak = new double[net.length];
        double p = 1;
        for (int k = 0; k < net.length; k++) {
            p = Math.max(p, net[k]);
            peak[k] = p;
        }
        for (int k : sampleIndices(time.length)) {
            t.add(time[k]);
            n.add((net[k] - 1) * 100);
            g.add((gross[k] - 1) * 100);
            dd.add(-(peak[k] - net[k]) / peak[k] * 100);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", t);
        out.put("netPct", n);
        out.put("grossPct", g);
        out.put("drawdownPct", dd);
        return out;
    }
}
