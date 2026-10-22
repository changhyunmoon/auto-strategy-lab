package com.mch.strategy.dataset;

import java.util.List;

/** 화면·API 로 내보내는 데이터세트 모양 */
public final class DatasetViews {

    private DatasetViews() {
    }

    public record Summary(
            long id,
            String name,
            String status,
            String statusLabel,
            boolean building,
            List<String> assets,
            String period,
            List<String> intervals,
            int columnCount,
            long rowCount,
            String createdAt,
            Long jobId,
            int progress,
            String step,
            String message
    ) {
    }

    public record ColumnView(int index, String name, String featureCode, String featureName, String group, String param) {
    }

    public record QualityView(String market, long expected, long actual, String fillRate, String notes, boolean ok) {
    }

    public record GapView(String market, String from, String to, long minutes) {
    }

    public record AssetOption(long id, String code) {
    }

    public record Detail(
            Summary summary,
            String rangeText,
            String warmupText,
            List<String> sourceLabels,
            List<ColumnView> columns,
            int featureColumnCount,
            List<QualityView> qualities,
            List<GapView> gaps,
            List<AssetOption> assets,
            List<String> intervals,
            String duration,
            /* 「같은 설정으로 새로 만들기」용 */
            List<Long> assetIds,
            List<String> sources,
            List<String> featureCodes,
            String fromDate,
            String toDate
    ) {
    }
}
