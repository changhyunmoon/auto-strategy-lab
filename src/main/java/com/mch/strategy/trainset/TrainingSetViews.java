package com.mch.strategy.trainset;

import java.util.List;
import java.util.Map;

/** 화면·API 로 내보내는 학습 데이터 모양 */
public final class TrainingSetViews {

    private TrainingSetViews() {
    }

    public record Summary(
            long id,
            String name,
            String status,
            String statusLabel,
            boolean building,
            long datasetId,
            String datasetName,
            List<String> assets,
            String interval,
            List<String> extraIntervals,
            List<String> featureGroups,
            String thresholdPct,
            String trainPeriod,
            String valPeriod,
            String testPeriod,
            long rowCount,
            int columnCount,
            String createdAt,
            Long jobId,
            int progress,
            String step,
            String message,
            long runCount
    ) {
    }

    public record Detail(
            Summary summary,
            boolean includeBarColumns,
            List<String> featureGroupLabels,
            List<String> columns,
            /* 구간·정답 분포 통계 (JS 가 표·차트로 그린다) */
            Map<String, Object> stats,
            /* 「같은 설정으로 새로 만들기」용 */
            Map<String, Object> copy
    ) {
    }
}
