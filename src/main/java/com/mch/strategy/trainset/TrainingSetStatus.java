package com.mch.strategy.trainset;

public enum TrainingSetStatus {
    PENDING("대기"),
    BUILDING("만드는 중"),
    READY("완료"),
    FAILED("실패");

    private final String label;

    TrainingSetStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
