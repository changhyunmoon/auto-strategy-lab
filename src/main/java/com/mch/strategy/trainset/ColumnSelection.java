package com.mch.strategy.trainset;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.mch.strategy.dataset.DatasetColumn;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureRegistry;

/** 기준 데이터세트의 열 중 학습 입력으로 쓸 열을 고른다. */
final class ColumnSelection {

    private ColumnSelection() {
    }

    /**
     * @param groups      지표 그룹 코드 (A~G)
     * @param includeBar  시고저종·거래량 원값을 넣을지. 가격 수준 자체는 기간마다 크게 달라 기본은 뺀다.
     */
    static List<DatasetColumn> select(List<DatasetColumn> columns, FeatureRegistry registry,
                                      Set<String> groups, boolean includeBar) {
        List<DatasetColumn> out = new ArrayList<>();
        for (DatasetColumn c : columns) {
            if (c.isBar()) {
                if (includeBar) {
                    out.add(c);
                }
                continue;
            }
            String group = registry.find(c.getFeatureCode()).map(FeatureDefinition::group).map(g -> g.getCode()).orElse("?");
            if (groups.contains(group)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 다른 간격에서는 지표 열만 붙인다 (봉 원값은 판단 간격 것만). */
    static List<DatasetColumn> featuresOnly(List<DatasetColumn> selected) {
        return selected.stream().filter(c -> !c.isBar()).toList();
    }

    static int barIndex(List<DatasetColumn> columns, String name) {
        return columns.stream().filter(c -> c.isBar() && c.getColumnName().equals(name))
                .mapToInt(DatasetColumn::getColumnIndex).findFirst()
                .orElseThrow(() -> new IllegalStateException("데이터세트에 " + name + " 열이 없습니다."));
    }
}
