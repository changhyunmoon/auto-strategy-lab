package com.mch.strategy.collect;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 마켓별 수집 이력. 성공한 구간은 다음 요청 때 다시 받지 않는 기준이 된다. */
@Entity
@Table(name = "collect_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectLog {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String RUNNING = "RUNNING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false)
    private Long marketId;

    @Column(name = "range_from", nullable = false)
    private Instant rangeFrom;

    @Column(name = "range_to", nullable = false)
    private Instant rangeTo;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public static CollectLog start(long marketId, Instant from, Instant to) {
        CollectLog log = new CollectLog();
        log.marketId = marketId;
        log.rangeFrom = from;
        log.rangeTo = to;
        log.status = RUNNING;
        log.requestedAt = Instant.now();
        return log;
    }

    public void succeed(int rowCount) {
        this.rowCount = rowCount;
        this.status = SUCCESS;
        this.finishedAt = Instant.now();
    }

    public void fail(int rowCount, String message) {
        this.rowCount = rowCount;
        this.status = FAILED;
        this.errorMessage = message;
        this.finishedAt = Instant.now();
    }
}
