-- 학습용 데이터세트와 모델 실험(학습·검증·최종 테스트).
-- 행 데이터는 DB 에 다시 저장하지 않고 데이터 폴더의 파일(학습용 행렬)로 만든다.

-- 백그라운드 작업이 데이터세트 외의 대상(학습 데이터 · 실험)도 가리킬 수 있게 한다.
ALTER TABLE job ADD COLUMN target_id BIGINT NULL AFTER dataset_id;
ALTER TABLE job ADD KEY idx_job_target (job_type, target_id);

CREATE TABLE training_set (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    name                VARCHAR(100)   NOT NULL,
    status              VARCHAR(20)    NOT NULL,              -- PENDING / BUILDING / READY / FAILED
    dataset_id          BIGINT         NOT NULL,              -- 기준 데이터세트 (지워져도 학습 데이터 파일은 남는다)
    dataset_name        VARCHAR(100)   NOT NULL,
    decision_interval   VARCHAR(5)     NOT NULL,              -- 한 행 = 이 간격의 봉 하나
    extra_intervals     JSON           NOT NULL,              -- 함께 붙일 다른 간격 ["5m","15m"] (완성 봉만)
    asset_ids           JSON           NOT NULL,
    feature_groups      JSON           NOT NULL,              -- 사용할 지표 그룹 ["A","B",...]
    include_bar_columns BOOLEAN        NOT NULL DEFAULT FALSE,-- 시고저종·거래량 원값을 입력에 넣을지
    label_method        VARCHAR(30)    NOT NULL,              -- NEXT_BAR_DIRECTION
    threshold_pct       DECIMAL(6, 3)  NOT NULL,              -- 0.1 / 0.3 / 0.5 / 0.7 / 1.0
    train_from          DATE           NOT NULL,              -- 날짜는 UTC, 끝 날짜 하루 전체 포함
    train_to            DATE           NOT NULL,
    val_from            DATE           NOT NULL,
    val_to              DATE           NOT NULL,
    test_from           DATE           NOT NULL,
    test_to             DATE           NOT NULL,
    row_count           BIGINT         NOT NULL DEFAULT 0,
    column_count        INT            NOT NULL DEFAULT 0,
    stats               JSON           NULL,                  -- 구간·자산별 행 수, 정답 분포, 제외 사유
    columns_json        JSON           NULL,                  -- 입력 열 이름 목록 (파일 열 순서)
    message             TEXT           NULL,
    created_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 실험 하나 = 학습 데이터 + 모델 + 설정. 상세 결과(혼동행렬·월별 성능 등)는 실험 폴더의 JSON 파일에 있다.
CREATE TABLE model_run (
    id                       BIGINT         NOT NULL AUTO_INCREMENT,
    name                     VARCHAR(150)   NOT NULL,
    training_set_id          BIGINT         NOT NULL,
    model_code               VARCHAR(40)    NOT NULL,
    params                   JSON           NOT NULL,
    train_unit               VARCHAR(20)    NOT NULL,         -- PER_ASSET / POOLED
    class_weight             VARCHAR(20)    NOT NULL,         -- NONE / BALANCED
    early_stopping           VARCHAR(20)    NOT NULL,         -- TRAIN_TAIL / VALIDATION / OFF
    seed                     INT            NOT NULL,
    max_train_rows           INT            NOT NULL,         -- 학습 단위마다 쓸 학습 행 수 상한 (넘으면 무작위 표본)
    permutation_importance   BOOLEAN        NOT NULL DEFAULT TRUE,
    memo                     TEXT           NULL,
    status                   VARCHAR(20)    NOT NULL,         -- PENDING / RUNNING / READY / FAILED
    train_accuracy           DOUBLE         NULL,
    train_macro_f1           DOUBLE         NULL,
    val_accuracy             DOUBLE         NULL,
    val_balanced_accuracy    DOUBLE         NULL,
    val_macro_f1             DOUBLE         NULL,
    val_log_loss             DOUBLE         NULL,
    val_baseline_macro_f1    DOUBLE         NULL,             -- 같은 검증 구간에서 「항상 최빈 클래스」의 점수
    test_status              VARCHAR(20)    NOT NULL DEFAULT 'NONE', -- NONE / RUNNING / DONE / FAILED
    test_accuracy            DOUBLE         NULL,
    test_balanced_accuracy   DOUBLE         NULL,
    test_macro_f1            DOUBLE         NULL,
    test_log_loss            DOUBLE         NULL,
    test_run_count           INT            NOT NULL DEFAULT 0,
    test_evaluated_at        DATETIME(3)    NULL,
    train_seconds            DOUBLE         NULL,
    message                  TEXT           NULL,
    created_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_model_run_set (training_set_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
