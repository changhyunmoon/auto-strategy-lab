package com.mch.strategy.feature;

import java.time.Instant;
import java.util.List;

import com.mch.strategy.indicator.ColumnSpec;
import com.mch.strategy.indicator.IndicatorKind;

/**
 * 화면 · 저장용 열 정보 (계산 함수 없이). 생성 뒤에는 거래쌍별 null 수 · 첫 유효 시각이 붙는다.
 *
 * @param index 파일 안의 열 번호
 */
public record ColumnView(
        int index,
        String name,
        IndicatorKind kind,
        String kindLabel,
        String bundle,
        String title,
        String formula,
        String read,
        List<String> inputs,
        String params,
        int warmup,
        List<MarketStat> stats
) {

    /**
     * @param nulls      값이 없는(null) 행 수
     * @param firstValid 처음 값이 나온 1분봉 시각 (모두 null 이면 없음)
     */
    public record MarketStat(long marketId, long nulls, Instant firstValid) {
    }

    public static ColumnView of(int index, ColumnSpec c) {
        return new ColumnView(index, c.name(), c.kind(), c.kind().getLabel(), c.bundle(), c.title(), c.formula(), c.read(),
                c.inputs(), c.params(), c.warmup(), null);
    }

    public ColumnView withStats(List<MarketStat> s) {
        return new ColumnView(index, name, kind, kindLabel, bundle, title, formula, read, inputs, params, warmup, s);
    }
}
