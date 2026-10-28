-- V2: 원본 1분봉 → 기술 지표 → H·P·L 정답 → 학습·검증·최종 테스트 분할.
-- V1 테이블(dataset · training_set · model_run …)은 지우지 않고 그대로 둔다 (V2 화면에서는 쓰지 않음).
-- 1분봉 원본(candle_1m)과 수집 이력(collect_log)은 V1 과 함께 쓴다. 이미 받은 기간은 다시 받지 않는다.
-- 큰 행 데이터(지표 값 · 정답 · 분할 소속)는 데이터 폴더의 파일에 두고, 여기에는 설정 · 요약 · 버전만 둔다.
-- 각 행의 id 가 곧 버전이다. 설정을 바꿔 다시 만들면 새 id(새 버전)가 생기고 이전 결과는 덮어쓰지 않는다.

CREATE TABLE v2_raw_dataset (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100)  NOT NULL,
    status        VARCHAR(20)   NOT NULL,            -- PENDING / BUILDING / READY / FAILED
    exchange      VARCHAR(20)   NOT NULL,            -- UPBIT / BINANCE
    market_ids    VARCHAR(200)  NOT NULL,            -- 쉼표로 구분한 market.id
    range_from    DATETIME(3)   NOT NULL,            -- 포함 (UTC)
    range_to      DATETIME(3)   NOT NULL,            -- 제외 (UTC)
    row_count     BIGINT        NOT NULL DEFAULT 0,
    quality       LONGTEXT      NULL,                -- 거래쌍별 입력 검사 결과 JSON
    message       TEXT          NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at   DATETIME(3)   NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE v2_feature_set (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100)  NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    raw_dataset_id  BIGINT        NOT NULL,
    config          LONGTEXT      NOT NULL,          -- 설정 스냅샷 (IndicatorConfig JSON)
    code_version    VARCHAR(30)   NOT NULL,
    planned_count   INT           NOT NULL DEFAULT 0, -- 중복 제거 전 열 수
    column_count    INT           NOT NULL DEFAULT 0, -- 실제 추가된 고유 열 수
    row_count       BIGINT        NOT NULL DEFAULT 0,
    columns_json    LONGTEXT      NULL,              -- 열 정의 + 거래쌍별 첫 유효 시각 · null 수
    message         TEXT          NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at     DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_v2_feature_raw (raw_dataset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 지표 생성 화면에서 「설정 저장」으로 남긴 설정 (데이터 없이 설정만)
CREATE TABLE v2_feature_preset (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100)  NOT NULL,
    config      LONGTEXT      NOT NULL,
    created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE v2_label_set (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    name             VARCHAR(100)  NOT NULL,
    description      TEXT          NULL,
    status           VARCHAR(20)   NOT NULL,
    feature_set_id   BIGINT        NOT NULL,
    config           LONGTEXT      NOT NULL,         -- LabelConfig JSON (H · P · L · 방향 · 진입 · 동시 도달 · 비용)
    explore_to       DATETIME(3)   NOT NULL,         -- 이 시각 이후 판단 행의 정답 분포는 탐색 중 숨긴다 (최종 테스트 후보)
    code_version     VARCHAR(30)   NOT NULL,
    row_count        BIGINT        NOT NULL DEFAULT 0,
    summary          LONGTEXT      NULL,             -- 거래쌍별 · 탐색/숨김 기간별 상태 건수
    message          TEXT          NULL,
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at      DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_v2_label_feature (feature_set_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE v2_split_plan (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100)  NOT NULL,
    description     TEXT          NULL,
    status          VARCHAR(20)   NOT NULL,
    label_set_id    BIGINT        NOT NULL,
    config          LONGTEXT      NOT NULL,          -- SplitConfig JSON (사용 기간 · 세 구간 날짜 · 경계 여유)
    code_version    VARCHAR(30)   NOT NULL,
    summary         LONGTEXT      NULL,              -- 거래쌍 · 구간별 행 수 · 제외 사유 · 정답 분포
    test_views      INT           NOT NULL DEFAULT 0,-- 최종 테스트 정답 분포를 연 횟수
    test_view_log   LONGTEXT      NULL,              -- 연 시각 목록 JSON
    message         TEXT          NULL,
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at     DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_v2_split_label (label_set_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
