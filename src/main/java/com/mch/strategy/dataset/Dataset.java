package com.mch.strategy.dataset;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 데이터세트 정의와 상태. 실제 값은 dataset_row 에 있다. */
@Entity
@Table(name = "dataset")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DatasetStatus status;

    @Column(name = "range_from", nullable = false)
    private Instant rangeFrom;

    @Column(name = "range_to", nullable = false)
    private Instant rangeTo;

    @Column(name = "warmup_from")
    private Instant warmupFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> intervals;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_ids", nullable = false)
    private List<Long> assetIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> sources;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    @Column(name = "column_count", nullable = false)
    private int columnCount;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Dataset create(String name, Instant from, Instant to, Instant warmupFrom,
                                 List<String> intervals, List<Long> assetIds, List<String> sources, int columnCount) {
        Dataset d = new Dataset();
        d.name = name;
        d.status = DatasetStatus.PENDING;
        d.rangeFrom = from;
        d.rangeTo = to;
        d.warmupFrom = warmupFrom;
        d.intervals = intervals;
        d.assetIds = assetIds;
        d.sources = sources;
        d.columnCount = columnCount;
        return d;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isBuilding() {
        return status != DatasetStatus.READY && status != DatasetStatus.FAILED;
    }

    public void changeStatus(DatasetStatus status) {
        this.status = status;
    }

    public void ready(long rowCount) {
        this.status = DatasetStatus.READY;
        this.rowCount = rowCount;
        this.message = null;
    }

    public void fail(String message) {
        this.message = (status == null ? "" : status.getLabel() + " 단계: ") + message;
        this.status = DatasetStatus.FAILED;
    }
}
