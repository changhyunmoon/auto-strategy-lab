package com.mch.strategy.feature;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** 등록된 모든 지표 빈을 모아 그룹별로 제공한다. */
@Component
public class FeatureRegistry {

    private final List<FeatureDefinition> features;

    public FeatureRegistry(List<FeatureDefinition> features) {
        this.features = features.stream()
                .sorted(Comparator.comparing(FeatureDefinition::group)) // 그룹 안에서는 정의한 순서 유지
                .toList();
    }

    public List<FeatureDefinition> all() {
        return features;
    }

    public Map<FeatureGroup, List<FeatureDefinition>> byGroup() {
        return features.stream().collect(Collectors.groupingBy(
                FeatureDefinition::group, LinkedHashMap::new, Collectors.toList()));
    }

    public Optional<FeatureDefinition> find(String code) {
        return features.stream().filter(f -> f.code().equals(code)).findFirst();
    }
}
