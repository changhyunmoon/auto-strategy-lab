package com.mch.strategy.dataset;

import java.util.List;

import com.mch.strategy.collect.CandleSeries;

/**
 * 모든 데이터세트의 앞 열은 봉 자체의 값이다 (시각화 캔들·학습 정답 생성에 쓴다).
 * 그 뒤에 선택한 지표 열이 이어진다.
 */
public final class DatasetColumns {

    public static final List<String> BAR_COLUMNS = List.of("open", "high", "low", "close", "volume", "quote_volume");

    private DatasetColumns() {
    }

    static double[] barValues(CandleSeries s, String column) {
        return switch (column) {
            case "open" -> s.open();
            case "high" -> s.high();
            case "low" -> s.low();
            case "close" -> s.close();
            case "volume" -> s.volume();
            case "quote_volume" -> s.quoteVolume();
            default -> null;
        };
    }
}
