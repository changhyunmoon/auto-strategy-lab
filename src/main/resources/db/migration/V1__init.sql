-- 모든 시각은 UTC로 저장한다.
-- interval, values 는 MySQL 예약어라 bar_interval, feature_values 로 이름을 바꿨다.

CREATE TABLE asset (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE market (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    asset_id        BIGINT       NOT NULL,
    exchange        VARCHAR(20)  NOT NULL,                -- UPBIT / BINANCE
    symbol          VARCHAR(30)  NOT NULL,                -- KRW-BTC / BTCUSDT
    quote_currency  VARCHAR(10)  NOT NULL,                -- KRW / USDT
    role            VARCHAR(20)  NOT NULL,                -- EXECUTION / REFERENCE
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_market_symbol (exchange, symbol),
    CONSTRAINT fk_market_asset FOREIGN KEY (asset_id) REFERENCES asset (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 거래소에서 받은 1분봉 원본. 데이터세트끼리 공유한다.
CREATE TABLE candle_1m (
    market_id         BIGINT          NOT NULL,
    open_time         DATETIME(3)     NOT NULL,
    open              DECIMAL(30, 10) NOT NULL,
    high              DECIMAL(30, 10) NOT NULL,
    low               DECIMAL(30, 10) NOT NULL,
    close             DECIMAL(30, 10) NOT NULL,
    volume            DECIMAL(30, 10) NOT NULL,
    quote_volume      DECIMAL(30, 10) NOT NULL,
    trade_count       INT             NULL,             -- 바이낸스만
    taker_buy_volume  DECIMAL(30, 10) NULL,             -- 바이낸스만
    taker_buy_quote   DECIMAL(30, 10) NULL,             -- 바이낸스만
    collected_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (market_id, open_time),
    CONSTRAINT fk_candle_1m_market FOREIGN KEY (market_id) REFERENCES market (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 5분·15분봉은 따로 저장하지 않고, 데이터세트를 만들 때 1분봉에서 재집계해
-- 시고저종·거래량을 dataset_row 의 앞 열로 함께 저장한다.

CREATE TABLE collect_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    market_id      BIGINT       NOT NULL,
    range_from     DATETIME(3)  NOT NULL,
    range_to       DATETIME(3)  NOT NULL,
    row_count      INT          NOT NULL DEFAULT 0,
    status         VARCHAR(20)  NOT NULL,
    error_message  TEXT         NULL,
    requested_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_collect_log_market (market_id, requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE dataset (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100)  NOT NULL,
    status        VARCHAR(20)   NOT NULL,              -- DatasetStatus
    range_from    DATETIME(3)   NOT NULL,
    range_to      DATETIME(3)   NOT NULL,
    warmup_from   DATETIME(3)   NULL,
    intervals     JSON          NOT NULL,              -- ["1m","5m","15m"]
    asset_ids     JSON          NOT NULL,              -- [1,2]
    sources       JSON          NOT NULL,              -- ["UPBIT","BINANCE"]
    row_count     BIGINT        NOT NULL DEFAULT 0,
    column_count  INT           NOT NULL DEFAULT 0,
    message       TEXT          NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 데이터세트가 가진 열 목록. dataset_row.feature_values 배열의 순서를 정한다.
CREATE TABLE dataset_feature (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    dataset_id    BIGINT        NOT NULL,
    feature_code  VARCHAR(50)   NOT NULL,
    params        JSON          NULL,
    column_name   VARCHAR(100)  NOT NULL,
    column_index  INT           NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dataset_feature_index (dataset_id, column_index),
    CONSTRAINT fk_dataset_feature_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 데이터세트 본체. 지표 값은 JSON 배열 한 칸에 저장한다.
CREATE TABLE dataset_row (
    dataset_id      BIGINT       NOT NULL,
    asset_id        BIGINT       NOT NULL,
    bar_interval    VARCHAR(5)   NOT NULL,
    open_time       DATETIME(3)  NOT NULL,
    is_warmup       BOOLEAN      NOT NULL DEFAULT FALSE,
    feature_values  JSON         NOT NULL,
    PRIMARY KEY (dataset_id, asset_id, bar_interval, open_time),
    CONSTRAINT fk_dataset_row_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE dataset_quality (
    id                BIGINT  NOT NULL AUTO_INCREMENT,
    dataset_id        BIGINT  NOT NULL,
    market_id         BIGINT  NOT NULL,
    expected_minutes  BIGINT  NOT NULL,
    actual_minutes    BIGINT  NOT NULL,
    duplicate_count   INT     NOT NULL DEFAULT 0,
    gaps              JSON    NULL,                    -- [{"from":..., "to":..., "minutes":...}]
    notes             TEXT    NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dataset_quality_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE job (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    job_type     VARCHAR(30)   NOT NULL,               -- DATASET_BUILD
    dataset_id   BIGINT        NULL,
    status       VARCHAR(20)   NOT NULL,               -- JobStatus
    progress     INT           NOT NULL DEFAULT 0,     -- 0~100
    step         VARCHAR(50)   NULL,
    message      TEXT          NULL,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at   DATETIME(3)   NULL,
    finished_at  DATETIME(3)   NULL,
    PRIMARY KEY (id),
    KEY idx_job_dataset (dataset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
