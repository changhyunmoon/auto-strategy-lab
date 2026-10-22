package com.mch.strategy.feature;

/** 지표 계산에 필요한 조건. 화면에서 조건이 안 맞으면 해당 지표를 비활성화한다. */
public enum FeatureRequirement {
    /** 바이낸스 1분봉 (매수 주도 거래량 · 체결 건수 · 선행 수익률) */
    BINANCE_CANDLES("바이낸스 1분봉 필요"),
    /** 업비트·바이낸스 둘 다 (거래소 간 비교) */
    BOTH_EXCHANGES("업비트·바이낸스 1분봉 모두 필요"),
    /** BTC·ETH 둘 다 (자산 간 비교) */
    BOTH_ASSETS("BTC·ETH 둘 다 선택 필요");

    private final String message;

    FeatureRequirement(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
