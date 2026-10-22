package com.mch.strategy.feature;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 빌더로 만드는 기본 지표 구현.
 * <pre>
 * Feature.of("rsi", "RSI", FeatureGroup.C_RANGE)
 *        .param("14").warmupBars(42).columns("rsi_14")
 *        .compute(in -> new double[][]{ ... });
 * </pre>
 * compute 가 돌려주는 배열 순서는 columns 순서와 같아야 한다.
 */
public final class Feature implements FeatureDefinition {

    private final String code;
    private final String name;
    private final FeatureGroup group;
    private final String paramText;
    private final Set<FeatureRequirement> requirements;
    private final int warmupBars;
    private final long warmupFixedMinutes;
    private final List<String> columns;
    private final Function<FeatureInput, double[][]> fn;

    private Feature(Builder b, Function<FeatureInput, double[][]> fn) {
        this.code = b.code;
        this.name = b.name;
        this.group = b.group;
        this.paramText = b.paramText;
        this.requirements = b.requirements;
        this.warmupBars = b.warmupBars;
        this.warmupFixedMinutes = b.warmupFixedMinutes;
        this.columns = b.columns;
        this.fn = fn;
    }

    public static Builder of(String code, String name, FeatureGroup group) {
        return new Builder(code, name, group);
    }

    @Override public String code() { return code; }
    @Override public String name() { return name; }
    @Override public FeatureGroup group() { return group; }
    @Override public String paramText() { return paramText; }
    @Override public Set<FeatureRequirement> requirements() { return requirements; }
    @Override public List<String> outputColumns() { return columns; }

    @Override
    public long warmupMinutes(int intervalMinutes) {
        return Math.max((long) warmupBars * intervalMinutes, warmupFixedMinutes);
    }

    @Override
    public Map<String, double[]> compute(FeatureInput input) {
        double[][] values = fn.apply(input);
        if (values.length != columns.size()) {
            throw new IllegalStateException(code + ": 열 개수가 맞지 않습니다.");
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i].length != input.size()) {
                throw new IllegalStateException(code + ": 값 개수가 봉 개수와 다릅니다.");
            }
            out.put(columns.get(i), values[i]);
        }
        return out;
    }

    public static final class Builder {
        private final String code;
        private final String name;
        private final FeatureGroup group;
        private String paramText = "";
        private Set<FeatureRequirement> requirements = Set.of();
        private int warmupBars = 0;
        private long warmupFixedMinutes = 0;
        private List<String> columns = List.of();

        private Builder(String code, String name, FeatureGroup group) {
            this.code = code;
            this.name = name;
            this.group = group;
        }

        public Builder param(String paramText) {
            this.paramText = paramText;
            return this;
        }

        public Builder requires(FeatureRequirement first, FeatureRequirement... rest) {
            this.requirements = Set.copyOf(EnumSet.of(first, rest));
            return this;
        }

        /** 필요한 과거 봉 개수 (봉 간격에 따라 시간이 달라진다) */
        public Builder warmupBars(int bars) {
            this.warmupBars = bars;
            return this;
        }

        /** 봉 간격과 상관없이 필요한 과거 시간(분). 예: 최근 24시간 → 1440 */
        public Builder warmupMinutes(long minutes) {
            this.warmupFixedMinutes = minutes;
            return this;
        }

        public Builder columns(String... columns) {
            this.columns = List.of(columns);
            return this;
        }

        public Feature compute(Function<FeatureInput, double[][]> fn) {
            if (columns.isEmpty()) {
                throw new IllegalStateException(code + ": columns 를 먼저 지정하세요.");
            }
            return new Feature(this, fn);
        }
    }
}
