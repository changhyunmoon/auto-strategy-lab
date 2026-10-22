package com.mch.strategy.instrument;

public enum Exchange {
    UPBIT("업비트"),
    BINANCE("바이낸스");

    private final String label;

    Exchange(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
