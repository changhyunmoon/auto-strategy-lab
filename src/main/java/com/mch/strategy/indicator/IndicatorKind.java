package com.mch.strategy.indicator;

/** V2 에서 확정한 여섯 지표 종류. 화면 · 결과 표의 순서도 이 순서다. */
public enum IndicatorKind {
    VOLUME("거래량"),
    MA("이동평균선"),
    RSI("RSI"),
    MACD("MACD"),
    ATR("ATR"),
    BB("볼린저 밴드");

    private final String label;

    IndicatorKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
