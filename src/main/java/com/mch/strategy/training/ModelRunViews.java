package com.mch.strategy.training;

import java.util.List;
import java.util.Map;

/** 화면·API 로 내보내는 실험 모양 */
public final class ModelRunViews {

    private ModelRunViews() {
    }

    public record Summary(
            long id,
            String name,
            long trainingSetId,
            String trainingSetName,
            String modelCode,
            String modelLabel,
            String family,
            String status,
            String statusLabel,
            boolean busy,
            String trainUnit,
            String balance,
            Map<String, Object> params,
            int seed,
            int maxTrainRows,
            Double trainMacroF1,
            Double valAccuracy,
            Double valBalancedAccuracy,
            Double valMacroF1,
            Double valLogLoss,
            Double valBaselineMacroF1,
            String testStatus,
            Double testAccuracy,
            Double testBalancedAccuracy,
            Double testMacroF1,
            Double testLogLoss,
            int testRunCount,
            String testEvaluatedAt,
            Double trainSeconds,
            String createdAt,
            Long jobId,
            int progress,
            String step,
            Long testJobId,
            int testProgress,
            String testStep,
            String message
    ) {
    }

    public record ParamView(String label, String value) {
    }

    public record Detail(
            Summary summary,
            String modelDescription,
            boolean probability,
            List<ParamView> params,
            String trainUnitDescription,
            String balanceDescription,
            String earlyStopping,
            boolean permutationImportance,
            String memo,
            /* 학습 데이터 요약 */
            String interval,
            String thresholdPct,
            List<String> assets,
            String trainPeriod,
            String valPeriod,
            String testPeriod,
            /* 「같은 설정으로 다시」용 */
            Map<String, Object> copy
    ) {
    }

    /** 모델 학습 화면의 모델 카드 */
    public record ModelOption(String code, String family, String label, String description, boolean probability,
                              boolean baseline, boolean earlyStopping, boolean classWeight,
                              List<ModelType.ParamSpec> params) {
    }

    public record Choice(String code, String label, String description) {
    }
}
