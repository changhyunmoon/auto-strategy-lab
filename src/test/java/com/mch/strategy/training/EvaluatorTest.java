package com.mch.strategy.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class EvaluatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void summary_matches_hand_computed_metrics() {
        // 실제: 하락 2 · 중립 4 · 상승 2
        int[] label = {0, 0, 1, 1, 1, 1, 2, 2};
        int[] pred = {0, 1, 1, 1, 1, 2, 2, 1};
        Map<String, Object> s = Evaluator.summaryOnly(label, pred, null);
        assertThat((Double) s.get("accuracy")).isCloseTo(5.0 / 8, offset(1e-12));
        // 재현율: 하락 1/2 · 중립 3/4 · 상승 1/2 → 균형 정확도 0.5833
        assertThat((Double) s.get("balancedAccuracy")).isCloseTo((0.5 + 0.75 + 0.5) / 3, offset(1e-12));
        // 정밀도: 하락 1/1 · 중립 3/5 · 상승 1/2
        double f1Down = 2 * 1.0 * 0.5 / 1.5, f1Neu = 2 * 0.6 * 0.75 / 1.35, f1Up = 0.5;
        assertThat((Double) s.get("macroF1")).isCloseTo((f1Down + f1Neu + f1Up) / 3, offset(1e-12));
        assertThat(s.get("logLoss")).isNull();
        List<Map<String, Object>> perClass = (List<Map<String, Object>>) s.get("perClass");
        assertThat(perClass.get(0).get("precision")).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void class_never_predicted_has_null_precision_not_zero() {
        int[] label = {0, 1, 2};
        int[] pred = {1, 1, 1};
        Map<String, Object> s = Evaluator.summaryOnly(label, pred, null);
        List<Map<String, Object>> perClass = (List<Map<String, Object>>) s.get("perClass");
        assertThat(perClass.get(0).get("precision")).isNull();
        assertThat(perClass.get(0).get("recall")).isEqualTo(0.0);
    }

    @Test
    void log_loss_uses_true_class_probability() {
        int[] label = {2, 1};
        double[][] proba = {{0.1, 0.1, 0.8}, {0.25, 0.5, 0.25}};
        Map<String, Object> s = Evaluator.summaryOnly(label, new int[]{2, 1}, proba);
        assertThat((Double) s.get("logLoss")).isCloseTo(-(Math.log(0.8) + Math.log(0.5)) / 2, offset(1e-12));
    }

    @Test
    void pr_curve_average_precision_for_perfect_ranking_is_one() {
        double[] score = {0.9, 0.8, 0.2, 0.1};
        int[] label = {2, 2, 1, 0};
        boolean[] use = {true, true, true, true};
        Map<String, Object> pr = Evaluator.prCurve(score, label, 2, use);
        assertThat((Double) pr.get("ap")).isCloseTo(1.0, offset(1e-12));
        assertThat((Double) pr.get("prevalence")).isCloseTo(0.5, offset(1e-12));
    }

    @Test
    @SuppressWarnings("unchecked")
    void full_result_is_valid_json_with_assets_periods_and_samples() throws Exception {
        int n = 400;
        int[] label = new int[n], pred = new int[n], asset = new int[n], base = new int[n];
        long[] time = new long[n];
        float[] fwd = new float[n];
        double[][] proba = new double[n][];
        for (int i = 0; i < n; i++) {
            label[i] = i % 3;
            pred[i] = (i % 5 == 0) ? (i + 1) % 3 : i % 3;
            asset[i] = i % 2;
            time[i] = 1_700_000_000_000L + i * 3_600_000L;
            fwd[i] = (label[i] - 1) * 0.4f;
            proba[i] = new double[]{0.2, 0.3, 0.5};
            base[i] = 1;
        }
        Map<String, Object> r = Evaluator.evaluate(new Evaluator.Input(label, pred, proba, time, asset, fwd,
                List.of("BTC", "ETH"), base), 1);
        assertThat((List<String>) r.get("assetKeys")).containsExactly("ALL", "BTC", "ETH");
        String json = new ObjectMapper().writeValueAsString(JsonSafe.clean(r));
        assertThat(json).doesNotContain("NaN").contains("\"monthly\"", "\"weekly\"", "\"pr\"", "\"calibration\"", "\"2_2\"");
        ModelRun.Scores scores = Evaluator.scores(r);
        assertThat(scores.accuracy()).isCloseTo(0.8, offset(1e-9));
        assertThat(Evaluator.baselineMacroF1(r)).isNotNull();
    }
}
