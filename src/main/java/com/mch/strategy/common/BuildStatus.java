package com.mch.strategy.common;

/** V2 결과물(원본 · 지표 · 정답 · 분할)의 만들기 상태 */
public enum BuildStatus {
    PENDING("대기"),
    BUILDING("만드는 중"),
    READY("완료"),
    /** 실패, 중지, 또는 앱이 꺼져서 끝나지 못함. 이어서 처리하지 않고 새로 만든다. */
    FAILED("실패");

    private final String label;

    BuildStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
