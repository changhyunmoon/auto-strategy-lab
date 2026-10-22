package com.mch.strategy.feature;

/** 노션 「1단계 최소 구성」의 지표 묶음 A~G. */
public enum FeatureGroup {
    A_RETURN("A", "수익률·모멘텀"),
    B_TREND("B", "추세·평균 대비 위치"),
    C_RANGE("C", "범위 안의 위치·강도"),
    D_VOLATILITY("D", "변동성"),
    E_VOLUME("E", "거래량·체결 방향"),
    F_RELATION("F", "자산·거래소 간 관계"),
    G_TIME("G", "시간");

    private final String code;
    private final String label;

    FeatureGroup(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
