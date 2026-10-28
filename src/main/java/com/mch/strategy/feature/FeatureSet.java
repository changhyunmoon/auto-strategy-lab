package com.mch.strategy.feature;

import java.time.Instant;

import com.mch.strategy.common.BuildStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 기술 지표 데이터셋 (파생 데이터셋): 원본 1분봉 행을 그대로 두고 지표 열을 더한 것.
 * 값은 데이터 폴더 v2/features/{id}/ 의 파일에 있다. 설정을 바꿔 다시 만들면 새 id(새 버전)가 된다.
 */
@Entity
@Table(name = "v2_feature_set")
@Getter
@Setter
@NoArgsConstructor
public class FeatureSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BuildStatus status;

    @Column(name = "raw_dataset_id", nullable = false)
    private Long rawDatasetId;

    /** IndicatorConfig JSON (정리된 설정 스냅샷) */
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String config;

    @Column(name = "code_version", nullable = false, length = 30)
    private String codeVersion;

    @Column(name = "planned_count", nullable = false)
    private int plannedCount;

    @Column(name = "column_count", nullable = false)
    private int columnCount;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    /** List&lt;ColumnView&gt; JSON (열 정의 + 거래쌍별 통계) */
    @Column(name = "columns_json", columnDefinition = "LONGTEXT")
    private String columnsJson;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
