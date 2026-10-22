package com.mch.strategy.dataset;

import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 마켓별 1분봉 품질 보고서 (데이터세트 기간 기준, 워밍업 제외) */
@Entity
@Table(name = "dataset_quality")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatasetQuality {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "market_id", nullable = false)
    private Long marketId;

    @Column(name = "expected_minutes", nullable = false)
    private long expectedMinutes;

    @Column(name = "actual_minutes", nullable = false)
    private long actualMinutes;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    /** [{"from": ISO, "to": ISO, "minutes": n}] 긴 순 최대 100개 */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<Map<String, Object>> gaps;

    private String notes;

    public DatasetQuality(long datasetId, long marketId, long expectedMinutes, long actualMinutes,
                          List<Map<String, Object>> gaps, String notes) {
        this.datasetId = datasetId;
        this.marketId = marketId;
        this.expectedMinutes = expectedMinutes;
        this.actualMinutes = actualMinutes;
        this.duplicateCount = 0; // (market_id, open_time) 기본키라 중복은 저장 단계에서 막힌다
        this.gaps = gaps;
        this.notes = notes;
    }
}
