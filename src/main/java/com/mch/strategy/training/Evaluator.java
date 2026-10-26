package com.mch.strategy.training;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 예측 결과로 화면에 쓸 평가 지표를 계산한다. 결과는 JSON 으로 저장되어 화면 JS 가 그대로 그린다.
 * <ul>
 *   <li>요약: 정확도 · 균형 정확도 · Macro F1 · Log loss, 클래스별 정밀도·재현율·F1</li>
 *   <li>혼동행렬, 실제·예측 건수, 월별·주별 성능, 예측 클래스별 다음 봉 수익률 분포</li>
 *   <li>확률을 내는 모델: 상승·하락 정밀도-재현율 곡선, 확률 보정, 확률 기준별 신호 표</li>
 * </ul>
 * 계산할 수 없는 값(표본 없음)은 null 로 둔다. 화면은 null 을 「계산 불가」로 보여준다 (0 과 구분).
 */
public final class Evaluator {

    public static final String[] LABELS = {"하락", "중립", "상승"};
    private static final double[] PROB_THRESHOLDS = {0.34, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.8, 0.9};
    private static final int SAMPLES_PER_CELL = 20;

    private Evaluator() {
    }

    /** 평가할 행 하나하나의 정보 (같은 길이의 배열) */
    public record Input(int[] label, int[] pred, double[][] proba, long[] time, int[] asset, float[] forward,
                        List<String> assets, int[] baselinePred) {
        int size() {
            return label.length;
        }
    }

    public static Map<String, Object> evaluate(Input in, long seed) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean hasProba = in.proba() != null;
        out.put("rows", in.size());
        out.put("hasProba", hasProba);
        out.put("labels", LABELS);

        List<String> keys = new ArrayList<>();
        keys.add("ALL");
        Map<String, Object> byAsset = new LinkedHashMap<>();
        byAsset.put("ALL", block(in, -1, hasProba));
        for (int a = 0; a < in.assets().size(); a++) {
            final int aa = a;
            if (Arrays.stream(in.asset()).anyMatch(v -> v == aa)) {
                keys.add(in.assets().get(a));
                byAsset.put(in.assets().get(a), block(in, a, hasProba));
            }
        }
        out.put("assetKeys", keys);
        out.put("byAsset", byAsset);
        if (in.baselinePred() != null) {
            out.put("baseline", summary(confusion(in.label(), in.baselinePred(), null, -1, in.asset()), null));
        }
        out.put("samples", samples(in, seed));
        return out;
    }

    /** 목록에 저장할 요약 점수 (전체 기준) */
    @SuppressWarnings("unchecked")
    public static ModelRun.Scores scores(Map<String, Object> result) {
        Map<String, Object> all = (Map<String, Object>) ((Map<String, Object>) result.get("byAsset")).get("ALL");
        Map<String, Object> s = (Map<String, Object>) all.get("summary");
        return new ModelRun.Scores((Double) s.get("accuracy"), (Double) s.get("balancedAccuracy"),
                (Double) s.get("macroF1"), (Double) s.get("logLoss"));
    }

    @SuppressWarnings("unchecked")
    public static Double baselineMacroF1(Map<String, Object> result) {
        Map<String, Object> b = (Map<String, Object>) result.get("baseline");
        return b == null ? null : (Double) b.get("macroF1");
    }

    /** 요약 점수만 (학습 구간 재평가용) */
    public static Map<String, Object> summaryOnly(int[] label, int[] pred, double[][] proba) {
        int[] noAsset = new int[label.length];
        return summary(confusion(label, pred, null, -1, noAsset), logLoss(label, proba, -1, noAsset));
    }

    private static Map<String, Object> block(Input in, int assetFilter, boolean hasProba) {
        Map<String, Object> b = new LinkedHashMap<>();
        long[][] cm = confusion(in.label(), in.pred(), null, assetFilter, in.asset());
        b.put("summary", summary(cm, hasProba ? logLoss(in.label(), in.proba(), assetFilter, in.asset()) : null));
        b.put("confusion", cm);
        b.put("monthly", periods(in, assetFilter, false));
        b.put("weekly", periods(in, assetFilter, true));
        b.put("returnByPred", returnByPred(in, assetFilter));
        if (hasProba) {
            Map<String, Object> pr = new LinkedHashMap<>();
            Map<String, Object> cal = new LinkedHashMap<>();
            Map<String, Object> th = new LinkedHashMap<>();
            for (int c : new int[]{2, 0}) {
                String key = c == 2 ? "up" : "down";
                double[] score = new double[in.size()];
                boolean[] use = new boolean[in.size()];
                for (int i = 0; i < in.size(); i++) {
                    use[i] = assetFilter < 0 || in.asset()[i] == assetFilter;
                    score[i] = in.proba()[i][c];
                }
                pr.put(key, prCurve(score, in.label(), c, use));
                cal.put(key, calibration(score, in.label(), c, use));
                th.put(key, thresholdTable(score, in.label(), in.forward(), c, use));
            }
            b.put("pr", pr);
            b.put("calibration", cal);
            b.put("thresholds", th);
        }
        return b;
    }

    static long[][] confusion(int[] label, int[] pred, int[] subset, int assetFilter, int[] asset) {
        long[][] cm = new long[3][3];
        for (int i = 0; i < label.length; i++) {
            if (assetFilter < 0 || asset[i] == assetFilter) {
                cm[label[i]][pred[i]]++;
            }
        }
        return cm;
    }

    static Map<String, Object> summary(long[][] cm, Double logLoss) {
        long n = 0, correct = 0;
        long[] support = new long[3], predicted = new long[3];
        for (int a = 0; a < 3; a++) {
            for (int p = 0; p < 3; p++) {
                n += cm[a][p];
                support[a] += cm[a][p];
                predicted[p] += cm[a][p];
            }
            correct += cm[a][a];
        }
        List<Map<String, Object>> perClass = new ArrayList<>();
        double f1Sum = 0, recallSum = 0;
        int f1Count = 0, recallCount = 0;
        for (int c = 0; c < 3; c++) {
            Double precision = predicted[c] == 0 ? null : (double) cm[c][c] / predicted[c];
            Double recall = support[c] == 0 ? null : (double) cm[c][c] / support[c];
            double pr = precision == null ? 0 : precision, rc = recall == null ? 0 : recall;
            Double f1 = support[c] == 0 && predicted[c] == 0 ? null : (pr + rc == 0 ? 0.0 : 2 * pr * rc / (pr + rc));
            if (f1 != null) {
                f1Sum += f1;
                f1Count++;
            }
            if (recall != null) {
                recallSum += recall;
                recallCount++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", LABELS[c]);
            m.put("precision", precision);
            m.put("recall", recall);
            m.put("f1", f1);
            m.put("support", support[c]);
            m.put("predicted", predicted[c]);
            perClass.add(m);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("n", n);
        s.put("accuracy", n == 0 ? null : (double) correct / n);
        s.put("balancedAccuracy", recallCount == 0 ? null : recallSum / recallCount);
        s.put("macroF1", f1Count == 0 ? null : f1Sum / f1Count);
        s.put("logLoss", logLoss);
        s.put("perClass", perClass);
        s.put("actualCounts", support);
        s.put("predictedCounts", predicted);
        return s;
    }

    static Double logLoss(int[] label, double[][] proba, int assetFilter, int[] asset) {
        if (proba == null) {
            return null;
        }
        double sum = 0;
        long n = 0;
        for (int i = 0; i < label.length; i++) {
            if (assetFilter < 0 || asset[i] == assetFilter) {
                sum -= Math.log(Math.max(proba[i][label[i]], 1e-15));
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    /** 월별 또는 주별(월요일 시작, UTC) 성능 */
    private static List<Map<String, Object>> periods(Input in, int assetFilter, boolean weekly) {
        TreeMap<String, long[][]> byPeriod = new TreeMap<>();
        for (int i = 0; i < in.size(); i++) {
            if (assetFilter >= 0 && in.asset()[i] != assetFilter) {
                continue;
            }
            LocalDate d = LocalDate.ofInstant(Instant.ofEpochMilli(in.time()[i]), ZoneOffset.UTC);
            String key = weekly ? d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString() : d.toString().substring(0, 7);
            byPeriod.computeIfAbsent(key, k -> new long[3][3])[in.label()[i]][in.pred()[i]]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byPeriod.forEach((k, cm) -> {
            Map<String, Object> s = summary(cm, null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", k);
            m.put("n", s.get("n"));
            m.put("accuracy", s.get("accuracy"));
            m.put("balancedAccuracy", s.get("balancedAccuracy"));
            m.put("macroF1", s.get("macroF1"));
            m.put("actualCounts", s.get("actualCounts"));
            m.put("predictedCounts", s.get("predictedCounts"));
            out.add(m);
        });
        return out;
    }

    /** 예측 클래스별 실제 다음 봉 수익률(%) 분포 */
    private static List<Map<String, Object>> returnByPred(Input in, int assetFilter) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            int n = 0;
            for (int i = 0; i < in.size(); i++) {
                if (in.pred()[i] == c && (assetFilter < 0 || in.asset()[i] == assetFilter)) {
                    n++;
                }
            }
            float[] v = new float[n];
            int k = 0;
            double sum = 0;
            for (int i = 0; i < in.size(); i++) {
                if (in.pred()[i] == c && (assetFilter < 0 || in.asset()[i] == assetFilter)) {
                    v[k++] = in.forward()[i];
                    sum += in.forward()[i];
                }
            }
            Arrays.sort(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", LABELS[c]);
            m.put("n", n);
            m.put("mean", n == 0 ? null : sum / n);
            m.put("p5", quantile(v, 0.05));
            m.put("p25", quantile(v, 0.25));
            m.put("p50", quantile(v, 0.5));
            m.put("p75", quantile(v, 0.75));
            m.put("p95", quantile(v, 0.95));
            out.add(m);
        }
        return out;
    }

    private static Double quantile(float[] sorted, double q) {
        if (sorted.length == 0) {
            return null;
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    /** 정밀도-재현율 곡선 (점 약 150개로 줄임) + 평균 정밀도(AP) + 양성 비율 */
    static Map<String, Object> prCurve(double[] score, int[] label, int cls, boolean[] use) {
        Integer[] order = sortedDesc(score, use);
        long positives = 0;
        for (int i : order) {
            if (label[i] == cls) {
                positives++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("positives", positives);
        out.put("prevalence", order.length == 0 ? null : (double) positives / order.length);
        if (positives == 0 || order.length == 0) {
            out.put("ap", null);
            out.put("points", List.of());
            return out;
        }
        List<double[]> pts = new ArrayList<>();
        long tp = 0, fp = 0;
        double ap = 0, prevRecall = 0;
        int step = Math.max(1, order.length / 150);
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            if (label[i] == cls) {
                tp++;
            } else {
                fp++;
            }
            boolean boundary = k == order.length - 1 || score[order[k + 1]] != score[i];
            if (boundary) {
                double precision = (double) tp / (tp + fp), recall = (double) tp / positives;
                ap += (recall - prevRecall) * precision;
                prevRecall = recall;
                if (k % step == 0 || k == order.length - 1 || pts.isEmpty()) {
                    pts.add(new double[]{round(recall), round(precision), round(score[i])});
                }
            }
        }
        out.put("ap", ap);
        out.put("points", pts);
        return out;
    }

    /** 확률 보정: 점수를 같은 개수의 10구간으로 나눠 평균 예측 확률 vs 실제 비율 */
    static List<Map<String, Object>> calibration(double[] score, int[] label, int cls, boolean[] use) {
        Integer[] order = sortedDesc(score, use);
        List<Map<String, Object>> out = new ArrayList<>();
        int n = order.length;
        if (n == 0) {
            return out;
        }
        int bins = Math.min(10, n);
        for (int b = bins - 1; b >= 0; b--) {
            int from = b * n / bins, to = (b + 1) * n / bins;
            double sum = 0;
            long hit = 0;
            for (int k = from; k < to; k++) {
                sum += score[order[k]];
                if (label[order[k]] == cls) {
                    hit++;
                }
            }
            int cnt = to - from;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("predicted", cnt == 0 ? null : sum / cnt);
            m.put("actual", cnt == 0 ? null : (double) hit / cnt);
            m.put("n", cnt);
            out.add(m);
        }
        return out;
    }

    /** 「상승 확률 X 이상일 때 신호」 기준별 신호 수 · 적중률 · 평균 다음 봉 수익률 */
    static List<Map<String, Object>> thresholdTable(double[] score, int[] label, float[] forward, int cls, boolean[] use) {
        int opposite = cls == 2 ? 0 : 2;
        List<Map<String, Object>> out = new ArrayList<>();
        for (double th : PROB_THRESHOLDS) {
            long signals = 0, hit = 0, wrong = 0;
            double sum = 0;
            for (int i = 0; i < score.length; i++) {
                if (use[i] && score[i] >= th) {
                    signals++;
                    sum += forward[i];
                    if (label[i] == cls) {
                        hit++;
                    } else if (label[i] == opposite) {
                        wrong++;
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("threshold", th);
            m.put("signals", signals);
            m.put("precision", signals == 0 ? null : (double) hit / signals);
            m.put("oppositeRate", signals == 0 ? null : (double) wrong / signals);
            m.put("meanReturn", signals == 0 ? null : sum / signals);
            out.add(m);
        }
        return out;
    }

    /** 혼동행렬 칸마다 사례 최대 20개 (전체 자산 기준, 무작위) */
    private static Map<String, Object> samples(Input in, long seed) {
        Random r = new Random(seed);
        List<List<Integer>> cells = new ArrayList<>();
        int[] seen = new int[9];
        for (int c = 0; c < 9; c++) {
            cells.add(new ArrayList<>());
        }
        for (int i = 0; i < in.size(); i++) {
            int cell = in.label()[i] * 3 + in.pred()[i];
            seen[cell]++;
            List<Integer> list = cells.get(cell);
            if (list.size() < SAMPLES_PER_CELL) {
                list.add(i);
            } else {
                int j = r.nextInt(seen[cell]);
                if (j < SAMPLES_PER_CELL) {
                    list.set(j, i);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int c = 0; c < 9; c++) {
            List<Map<String, Object>> rows = new ArrayList<>();
            cells.get(c).stream().sorted((a, b) -> Long.compare(in.time()[a], in.time()[b])).forEach(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", in.time()[i]);
                m.put("asset", in.assets().get(in.asset()[i]));
                m.put("forward", (double) in.forward()[i]);
                m.put("proba", in.proba() == null ? null : roundAll(in.proba()[i]));
                rows.add(m);
            });
            out.put((c / 3) + "_" + (c % 3), rows);
        }
        return out;
    }

    private static Integer[] sortedDesc(double[] score, boolean[] use) {
        int n = 0;
        for (boolean u : use) {
            if (u) {
                n++;
            }
        }
        Integer[] idx = new Integer[n];
        int k = 0;
        for (int i = 0; i < score.length; i++) {
            if (use[i]) {
                idx[k++] = i;
            }
        }
        Arrays.sort(idx, (a, b) -> Double.compare(score[b], score[a]));
        return idx;
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }

    private static double[] roundAll(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = Math.round(v[i] * 1e4) / 1e4;
        }
        return out;
    }
}
