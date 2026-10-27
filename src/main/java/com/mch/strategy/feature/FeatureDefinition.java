package com.mch.strategy.feature;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 보조지표 하나. 이 인터페이스를 구현한 빈을 추가하면 데이터세트 만들기 화면의 선택지에 자동으로 나타난다.
 * 보통은 {@link Feature#of} 빌더로 만든다 (예: feature/definitions/RangeFeatures).
 */
public interface FeatureDefinition {

    /** 고유 코드. 예: rsi */
    String code();

    /** 화면 표시 이름. 예: RSI */
    String name();

    FeatureGroup group();

    /** 화면에 보여줄 파라미터 설명. 예: 14 */
    String paramText();

    Set<FeatureRequirement> requirements();

    /** 계산이 안정되기까지 필요한 과거 기간(분). 데이터세트 시작일보다 이만큼 앞서 받는다. */
    long warmupMinutes(int intervalMinutes);

    /** 만들어지는 열 이름. 예: rsi_14 */
    List<String> outputColumns();

    /** 열 이름 → 봉별 값. 계산할 수 없는 봉은 NaN. */
    Map<String, double[]> compute(FeatureInput input);
}
