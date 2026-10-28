package com.mch.strategy.indicator;

import java.util.List;

/**
 * 설정을 펼친 결과: 실제로 만들 열(중복 제거 후), 묶음별 개수, 중복 · 오류 · 경고.
 *
 * @param columns          실제로 추가될 고유 열 (계산 · 저장 순서)
 * @param plannedCount     중복 제거 전 개수 (노션 문서의 조합 수 공식과 같은 값)
 * @param duplicates       같은 값이라 하나만 남긴 열
 * @param messages         오류(ERROR: 생성 차단) · 경고(WARN: 일부 조합을 만들지 않음) · 안내(INFO)
 * @param bundles          지표 · 묶음별 계획 수와 실제 추가 수
 * @param maxWarmup        가장 긴 워밍업 (구간 시작부터 모든 열이 값을 갖기까지 필요한 봉 수)
 */
public record IndicatorPlan(
        List<ColumnSpec> columns,
        int plannedCount,
        List<Duplicate> duplicates,
        List<Message> messages,
        List<BundleCount> bundles,
        int maxWarmup
) {

    public record Duplicate(String name, String sameAs, IndicatorKind kind) {
    }

    public record Message(String level, IndicatorKind kind, String text) {
    }

    public record BundleCount(IndicatorKind kind, String bundle, String label, int planned, int added) {
    }

    public boolean ok() {
        return messages.stream().noneMatch(m -> "ERROR".equals(m.level())) && !columns.isEmpty();
    }

    public int countFor(IndicatorKind kind) {
        return (int) columns.stream().filter(c -> c.kind() == kind).count();
    }
}
