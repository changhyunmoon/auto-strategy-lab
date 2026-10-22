package com.mch.strategy.dataset;

public enum DatasetStatus {
    PENDING("대기"),
    COLLECTING("원본 수집"),
    AGGREGATING("재집계"),
    COMPUTING("지표 계산"),
    CHECKING("품질 검사"),
    READY("완료"),
    FAILED("실패");

    private final String label;

    DatasetStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
