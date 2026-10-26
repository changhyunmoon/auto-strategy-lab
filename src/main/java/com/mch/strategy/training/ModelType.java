package com.mch.strategy.training;

import java.util.List;
import java.util.Map;

/**
 * 고를 수 있는 모델 목록. 새 모델은 여기에 하나 추가하고 {@link ModelTrainer} 에서 학습 방법을 정한다.
 * 노션 「M — 모델 후보 조사」의 1차 후보 중 이 PC(스마트 앱 컨트롤)에서 순수 Java 로 돌릴 수 있는 것들이다.
 */
public enum ModelType {

    BASELINE_MAJORITY("기준", "항상 최빈 클래스",
            "학습 구간에서 가장 많았던 정답(보통 중립)만 예측합니다. 정확도가 높아 보여도 이 모델보다 나은지가 첫 기준입니다.",
            true, List.of()),
    BASELINE_STRATIFIED("기준", "비율대로 무작위",
            "학습 구간의 정답 비율대로 무작위로 고릅니다. 「찍기」 수준의 Macro F1 이 얼마인지 보여줍니다.",
            true, List.of()),
    BASELINE_PERSISTENCE("기준", "직전 봉 방향 유지",
            "방금 끝난 봉의 수익률을 같은 기준 %로 나눠 그대로 다음 봉 정답으로 예측합니다. 단순 모멘텀이 통하는지 보는 시계열 기본 기준입니다.",
            false, List.of()),
    LOGISTIC("선형", "로지스틱 회귀",
            "표준화한 지표의 가중합으로 상승·중립·하락 확률을 계산합니다. 빠르고, 단순한 관계의 기준이 됩니다.",
            true, List.of(
                    ParamSpec.ofDouble("lambda", "L2 규제 강도", 0.1, 0, 1000, "클수록 가중치를 작게 눌러 과적합을 줄입니다."),
                    ParamSpec.ofInt("maxIter", "최대 반복 수", 300, 10, 5000, "최적화 반복 횟수 상한입니다."))),
    DECISION_TREE("트리", "결정트리 (작은 트리)",
            "몇 가지 조건으로 차례로 나눠 예측합니다. 혼자서는 약하지만 규칙을 읽기 쉬운 설명용 모델입니다.",
            true, List.of(
                    ParamSpec.ofInt("maxDepth", "최대 깊이", 6, 2, 30, "질문을 몇 단계까지 할지입니다."),
                    ParamSpec.ofInt("maxNodes", "최대 리프 수", 64, 2, 100000, "끝 마디(리프) 개수 상한입니다."),
                    ParamSpec.ofInt("nodeSize", "리프 최소 표본", 200, 1, 1000000, "리프 하나에 들어갈 최소 행 수입니다. 클수록 단순해집니다."))),
    RANDOM_FOREST("트리", "랜덤 포레스트",
            "무작위로 만든 많은 트리의 투표로 예측합니다. 비선형 관계를 잡고 과적합에 비교적 강합니다.",
            true, List.of(
                    ParamSpec.ofInt("ntrees", "트리 수", 200, 10, 2000, "많을수록 안정적이지만 오래 걸립니다."),
                    ParamSpec.ofInt("maxDepth", "최대 깊이", 14, 2, 60, ""),
                    ParamSpec.ofInt("maxNodes", "트리당 최대 리프 수", 2048, 2, 1000000, ""),
                    ParamSpec.ofInt("nodeSize", "리프 최소 표본", 50, 1, 1000000, ""),
                    ParamSpec.ofInt("mtry", "분할마다 볼 열 수 (0 = √열 수)", 0, 0, 10000, ""),
                    ParamSpec.ofDouble("subsample", "트리마다 쓸 행 비율", 0.5, 0.05, 1, "1 이면 복원 추출, 1 미만이면 비복원 추출입니다."))),
    GRADIENT_BOOSTING("트리", "그래디언트 부스팅",
            "앞 트리의 오류를 보완하는 작은 트리를 차례로 더합니다. 표 데이터에서 가장 강한 계열입니다 (LightGBM·HistGradientBoosting 과 같은 방식).",
            true, List.of(
                    ParamSpec.ofInt("ntrees", "트리 수 (최대)", 300, 10, 5000, "모두 학습한 뒤, 조기 종료를 켜면 그중 앞부분만 남길 수 있습니다."),
                    ParamSpec.ofDouble("shrinkage", "학습률", 0.05, 0.001, 1, "작을수록 천천히, 안정적으로 배웁니다."),
                    ParamSpec.ofInt("maxDepth", "최대 깊이", 8, 2, 60, ""),
                    ParamSpec.ofInt("maxNodes", "트리당 리프 수", 16, 2, 4096, ""),
                    ParamSpec.ofInt("nodeSize", "리프 최소 표본", 100, 1, 1000000, ""),
                    ParamSpec.ofDouble("subsample", "트리마다 쓸 행 비율", 0.7, 0.05, 1, ""),
                    ParamSpec.ofInt("patience", "조기 종료 인내 (트리 수)", 30, 1, 1000, "트리를 늘려도 조기 종료 구간의 Log loss 가 이만큼 나아지지 않으면 그 뒤 트리는 버립니다.")));

    private final String family;
    private final String label;
    private final String description;
    private final boolean probability;
    private final List<ParamSpec> params;

    ModelType(String family, String label, String description, boolean probability, List<ParamSpec> params) {
        this.family = family;
        this.label = label;
        this.description = description;
        this.probability = probability;
        this.params = params;
    }

    public String getFamily() {
        return family;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /** 클래스 확률을 내는지. 아니면 Log loss · 정밀도-재현율 곡선 · 확률 보정은 계산하지 않는다 */
    public boolean isProbability() {
        return probability;
    }

    public List<ParamSpec> getParams() {
        return params;
    }

    public boolean isBaseline() {
        return name().startsWith("BASELINE_");
    }

    public boolean supportsEarlyStopping() {
        return this == GRADIENT_BOOSTING;
    }

    public boolean supportsClassWeight() {
        return this == RANDOM_FOREST;
    }

    /** 요청 값 검증 + 빠진 값은 기본값으로 채운다 */
    public Map<String, Object> resolve(Map<String, Object> input) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (ParamSpec p : params) {
            Object raw = input == null ? null : input.get(p.key());
            out.put(p.key(), p.parse(raw));
        }
        return out;
    }

    /** 모델 설정 항목 하나. type = int · double */
    public record ParamSpec(String key, String label, String type, double defaultValue, double min, double max, String help) {

        static ParamSpec ofInt(String key, String label, int def, int min, int max, String help) {
            return new ParamSpec(key, label, "int", def, min, max, help);
        }

        static ParamSpec ofDouble(String key, String label, double def, double min, double max, String help) {
            return new ParamSpec(key, label, "double", def, min, max, help);
        }

        Object parse(Object raw) {
            double v;
            if (raw == null || String.valueOf(raw).isBlank()) {
                v = defaultValue;
            } else {
                try {
                    v = Double.parseDouble(String.valueOf(raw));
                } catch (NumberFormatException e) {
                    throw com.mch.strategy.common.ApiException.badRequest(label + ": 숫자를 입력하세요.");
                }
            }
            if (!Double.isFinite(v) || v < min || v > max) {
                throw com.mch.strategy.common.ApiException.badRequest(label + "는 " + fmt(min) + " ~ " + fmt(max) + " 사이여야 합니다.");
            }
            if (type.equals("int")) {
                if (v != Math.rint(v)) {
                    throw com.mch.strategy.common.ApiException.badRequest(label + "는 정수여야 합니다.");
                }
                return (int) v;
            }
            return v;
        }

        private static String fmt(double v) {
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        }
    }
}
