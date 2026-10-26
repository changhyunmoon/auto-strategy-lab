package com.mch.strategy.training;

import java.io.Serializable;
import java.util.List;

/**
 * 실험 하나의 학습 결과 묶음 (model.bin 으로 저장한다).
 * 자산별 모델이면 units[자산 번호], 통합 모델이면 units[0] 하나만 있다.
 */
public record RunModel(
        RunOptions.TrainUnit trainUnit,
        List<String> columns,
        List<String> assets,
        Preprocessor[] preprocessors,
        UnitModel[] units,
        /* 학습 단위별 학습 표본의 클래스 비율 (기준 비교용) */
        double[][] priors
) implements Serializable {

    /** 통합 모델이고 자산이 둘 이상이면 자산 구분 열(asset=코드)을 입력 끝에 더한다 */
    public boolean oneHot() {
        return trainUnit == RunOptions.TrainUnit.POOLED && assets.size() > 1;
    }

    public int inputCount() {
        return columns.size() + (oneHot() ? assets.size() : 0);
    }

    /** 입력 열 이름 (자산 구분 열 포함) */
    public List<String> inputNames() {
        if (!oneHot()) {
            return columns;
        }
        List<String> out = new java.util.ArrayList<>(columns);
        assets.forEach(a -> out.add("asset=" + a));
        return out;
    }

    public int unitOf(int assetIndex) {
        return trainUnit == RunOptions.TrainUnit.PER_ASSET ? assetIndex : 0;
    }

    /** 파일의 한 행 → 모델 입력 (결측 채우기 전) */
    public double[] input(TrainingData d, int row, double[] buffer) {
        double[] out = new double[inputCount()];
        d.row(row, buffer);
        System.arraycopy(buffer, 0, out, 0, d.cols);
        if (oneHot()) {
            out[d.cols + d.asset[row]] = 1;
        }
        return out;
    }

    /** 학습 구간 최빈 클래스 */
    public int majority(int unit) {
        return UnitModel.argmax(priors[unit]);
    }
}
