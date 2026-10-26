package com.mch.strategy.training;

/** 실험 공통 설정의 선택지 */
public final class RunOptions {

    private RunOptions() {
    }

    /** 학습 단위 */
    public enum TrainUnit {
        PER_ASSET("자산별 모델", "BTC·ETH 를 각각 따로 학습합니다 (기본)."),
        POOLED("통합 모델", "모든 자산을 한 모델로 학습하고, 자산 구분 열을 입력에 더합니다.");

        private final String label;
        private final String description;

        TrainUnit(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    /** 정답 불균형 처리. 학습 구간 표본에만 적용한다 */
    public enum Balance {
        NONE("미적용", "학습 표본을 그대로 씁니다."),
        UNDERSAMPLE("다수 클래스 줄이기", "가장 많은 클래스(보통 중립)를 두 번째로 많은 클래스 수만큼만 남겨 학습합니다. 모든 모델에 적용됩니다."),
        CLASS_WEIGHT("클래스 가중치", "적은 클래스에 더 큰 가중치를 줍니다. 랜덤 포레스트만 지원합니다.");

        private final String label;
        private final String description;

        Balance(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }

    /** 검증 방식 */
    public enum Validation {
        HOLDOUT("한 번 나누기", "학습 구간으로 학습하고 검증 구간으로 한 번 평가합니다. 가장 빠릅니다."),
        WALK_FORWARD_EXPANDING("확장형 워크포워드", "학습 + 검증 구간을 시간순 블록으로 나눠, 앞쪽 전체로 학습하고 다음 블록을 검증하기를 반복합니다. 한 시기의 운에 덜 휘둘립니다."),
        WALK_FORWARD_ROLLING("이동형 워크포워드", "확장형과 같지만 학습은 검증 직전 최근 N일만 씁니다. 오래된 시장 패턴을 버렸을 때와 비교할 수 있습니다.");

        private final String label;
        private final String description;

        Validation(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }

        public boolean isWalkForward() {
            return this != HOLDOUT;
        }
    }

    /** 그래디언트 부스팅의 조기 종료에 쓸 구간 */
    public enum EarlyStopping {
        TRAIN_TAIL("학습 구간 끝 10%", "학습 구간의 시간상 마지막 10%를 떼어 두고, 그 구간 Log loss 가 가장 낮은 트리 수만 남깁니다. 검증 구간을 건드리지 않습니다 (기본)."),
        VALIDATION("검증 구간", "검증 구간으로 남길 트리 수를 정합니다. 검증 점수가 조금 낙관적으로 나올 수 있습니다."),
        OFF("끄기", "정한 트리 수를 모두 학습합니다.");

        private final String label;
        private final String description;

        EarlyStopping(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }
    }
}
