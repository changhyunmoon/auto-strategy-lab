package com.mch.strategy.training;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.mch.strategy.trainset.TrainingRows;

import smile.classification.Classifier;
import smile.classification.DecisionTree;
import smile.classification.GradientTreeBoost;
import smile.classification.LogisticRegression;
import smile.classification.RandomForest;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.vector.ValueVector;

/**
 * 학습 단위(자산 하나 또는 통합) 하나의 학습된 모델.
 * 입력은 결측을 채운 값이고, 출력은 항상 [하락, 중립, 상승] 3칸 확률과 예측 클래스다.
 */
public interface UnitModel extends Serializable {

    /**
     * @param x        결측을 채운 입력 행들
     * @param prev     행마다 직전 봉 수익률(%) — 「직전 봉 방향 유지」만 쓴다
     * @param pred     예측 클래스 (0 하락 · 1 중립 · 2 상승)
     * @param proba    클래스 확률. 확률을 내지 않는 모델은 null 을 넘겨도 된다
     */
    void predict(double[][] x, double[] prev, int[] pred, double[][] proba);

    /** 모델이 자체로 계산한 변수 중요도 (입력 열 순서). 없으면 null */
    default double[] importance() {
        return null;
    }

    static int argmax(double[] p) {
        int best = 0;
        for (int c = 1; c < p.length; c++) {
            if (p[c] > p[best]) {
                best = c;
            }
        }
        return best;
    }

    /** 항상 같은 클래스. 확률은 학습 구간의 클래스 비율 */
    record Constant(int cls, double[] prior) implements UnitModel {
        @Override
        public void predict(double[][] x, double[] prev, int[] pred, double[][] proba) {
            for (int i = 0; i < x.length; i++) {
                pred[i] = cls;
                if (proba != null) {
                    proba[i] = prior.clone();
                }
            }
        }
    }


    /** 직전 N봉의 방향(같은 기준으로 나눈 상승·중립·하락)을 그대로 예측. 확률은 내지 않는다 */
    record Persistence() implements UnitModel {
        @Override
        public void predict(double[][] x, double[] prev, int[] pred, double[][] proba) {
            for (int i = 0; i < x.length; i++) {
                pred[i] = Double.isNaN(prev[i]) ? TrainingRows.NEUTRAL : (int) prev[i];
            }
        }
    }

    /** 로지스틱 회귀. 학습 표본의 평균·표준편차로 표준화해서 넣는다 */
    record Logistic(LogisticRegression model, double[] mean, double[] std) implements UnitModel {
        @Override
        public void predict(double[][] x, double[] prev, int[] pred, double[][] proba) {
            int[] classes = model.classes();
            double[] post = new double[model.numClasses()];
            double[] z = new double[mean.length];
            for (int i = 0; i < x.length; i++) {
                for (int j = 0; j < z.length; j++) {
                    z[j] = (x[i][j] - mean[j]) / std[j];
                }
                model.predict(z, post);
                double[] p3 = map(classes, post);
                pred[i] = argmax(p3);
                if (proba != null) {
                    proba[i] = p3;
                }
            }
        }

        @Override
        public double[] importance() {
            // 표준화된 입력의 계수 크기 평균 (클래스마다의 계수 절댓값 평균)
            double[] out = new double[mean.length];
            if (model instanceof LogisticRegression.Multinomial m) {
                double[][] w = m.coefficients();
                for (double[] row : w) {
                    for (int j = 0; j < out.length && j < row.length; j++) {
                        out[j] += Math.abs(row[j]) / w.length;
                    }
                }
                return out;
            }
            return null;
        }
    }

    /** Smile 의 DataFrame 기반 모델 (결정트리 · 랜덤 포레스트 · 그래디언트 부스팅) */
    record SmileFrame(Classifier<Tuple> model, int inputCount) implements UnitModel {
        @Override
        public void predict(double[][] x, double[] prev, int[] pred, double[][] proba) {
            DataFrame df = frame(x, null);
            int[] classes = model.classes();
            double[] post = new double[model.numClasses()];
            for (int i = 0; i < x.length; i++) {
                model.predict(df.get(i), post);
                double[] p3 = map(classes, post);
                pred[i] = argmax(p3);
                if (proba != null) {
                    proba[i] = p3;
                }
            }
        }

        @Override
        public double[] importance() {
            double[] imp = null;
            if (model instanceof RandomForest rf) {
                imp = rf.importance();
            } else if (model instanceof GradientTreeBoost gb) {
                imp = gb.importance();
            } else if (model instanceof DecisionTree dt) {
                imp = dt.importance();
            }
            if (imp == null || imp.length < inputCount) {
                return null;
            }
            return java.util.Arrays.copyOf(imp, inputCount);
        }
    }

    /** Smile 모델의 클래스 순서 → [하락, 중립, 상승] (학습에 없던 클래스는 0) */
    static double[] map(int[] classes, double[] post) {
        double[] p3 = new double[3];
        for (int c = 0; c < classes.length; c++) {
            if (classes[c] >= 0 && classes[c] < 3) {
                p3[classes[c]] = post[c];
            }
        }
        return p3;
    }

    /** 열 이름 f0, f1, … + 정답 열 y (예측할 때는 0 으로 채운다) */
    static DataFrame frame(double[][] x, int[] y) {
        int n = x.length;
        int p = n == 0 ? 0 : x[0].length;
        List<ValueVector> cols = new ArrayList<>(p + 1);
        for (int j = 0; j < p; j++) {
            double[] c = new double[n];
            for (int i = 0; i < n; i++) {
                c[i] = x[i][j];
            }
            cols.add(ValueVector.of("f" + j, c));
        }
        cols.add(ValueVector.of("y", y == null ? new int[n] : y));
        return new DataFrame(cols.toArray(new ValueVector[0]));
    }
}
