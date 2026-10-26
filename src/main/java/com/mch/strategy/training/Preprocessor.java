package com.mch.strategy.training;

import java.io.Serializable;

/**
 * 결측 채우기와 표준화 기준. 학습 표본으로만 계산하고, 검증·테스트에는 같은 값을 그대로 적용한다.
 * 결측(NaN)은 학습 표본의 평균으로 채운다.
 */
public record Preprocessor(double[] mean, double[] std) implements Serializable {

    public static Preprocessor fit(double[][] x) {
        int p = x.length == 0 ? 0 : x[0].length;
        double[] sum = new double[p], sq = new double[p];
        long[] n = new long[p];
        for (double[] row : x) {
            for (int j = 0; j < p; j++) {
                double v = row[j];
                if (Double.isFinite(v)) {
                    sum[j] += v;
                    sq[j] += v * v;
                    n[j]++;
                }
            }
        }
        double[] mean = new double[p], std = new double[p];
        for (int j = 0; j < p; j++) {
            mean[j] = n[j] == 0 ? 0 : sum[j] / n[j];
            double var = n[j] == 0 ? 0 : sq[j] / n[j] - mean[j] * mean[j];
            std[j] = var > 1e-24 ? Math.sqrt(var) : 1;
        }
        return new Preprocessor(mean, std);
    }

    /** 결측을 평균으로 채운다 (제자리 변경) */
    public void impute(double[] row) {
        for (int j = 0; j < row.length; j++) {
            if (!Double.isFinite(row[j])) {
                row[j] = mean[j];
            }
        }
    }

    public void impute(double[][] rows) {
        for (double[] r : rows) {
            impute(r);
        }
    }
}
