package com.mch.strategy.training;

public enum ModelRunStatus {
    PENDING("대기"),
    RUNNING("학습 중"),
    READY("완료"),
    FAILED("실패");

    private final String label;

    ModelRunStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
