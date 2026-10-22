package com.mch.strategy.instrument;

public enum MarketRole {
    /** 실제로 매매할 시장 (업비트 원화 현물) */
    EXECUTION("실행"),
    /** 참고용 해외 시장 (바이낸스) */
    REFERENCE("참고");

    private final String label;

    MarketRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
