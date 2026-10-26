package com.mch.strategy.trainset;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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

/**
 * 학습용 데이터세트: 기준 데이터세트 + 사용할 열 + 정답 기준 + 학습·검증·최종 테스트 날짜.
 * 실제 행렬은 데이터 폴더의 파일(trainsets/{id}/)에 있다.
 */
@Entity
@Table(name = "training_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainingSet {

    public static final String LABEL_NEXT_BAR = "NEXT_BAR_DIRECTION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrainingSetStatus status;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "dataset_name", nullable = false, length = 100)
    private String datasetName;

    @Column(name = "decision_interval", nullable = false, length = 5)
    private String decisionInterval;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_intervals", nullable = false)
    private List<String> extraIntervals;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_ids", nullable = false)
    private List<Long> assetIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_groups", nullable = false)
    private List<String> featureGroups;

    @Column(name = "include_bar_columns", nullable = false)
    private boolean includeBarColumns;

    @Column(name = "label_method", nullable = false, length = 30)
    private String labelMethod;

    @Column(name = "threshold_pct", nullable = false, precision = 6, scale = 3)
    private BigDecimal thresholdPct;

    @Column(name = "train_from", nullable = false)
    private LocalDate trainFrom;
    @Column(name = "train_to", nullable = false)
    private LocalDate trainTo;
    @Column(name = "val_from", nullable = false)
    private LocalDate valFrom;
    @Column(name = "val_to", nullable = false)
    private LocalDate valTo;
    @Column(name = "test_from", nullable = false)
    private LocalDate testFrom;
    @Column(name = "test_to", nullable = false)
    private LocalDate testTo;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    @Column(name = "column_count", nullable = false)
    private int columnCount;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> stats;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns_json")
    private List<String> columns;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static TrainingSet create(CreateTrainingSetRequest r, String datasetName, List<Long> assetIds,
                                     List<String> extraIntervals, List<String> featureGroups) {
        TrainingSet t = new TrainingSet();
        t.name = r.name().trim();
        t.status = TrainingSetStatus.PENDING;
        t.datasetId = r.datasetId();
        t.datasetName = datasetName;
        t.decisionInterval = r.decisionInterval();
        t.extraIntervals = extraIntervals;
        t.assetIds = assetIds;
        t.featureGroups = featureGroups;
        t.includeBarColumns = r.includeBarColumns();
        t.labelMethod = LABEL_NEXT_BAR;
        t.thresholdPct = r.thresholdPct();
        t.trainFrom = r.trainFrom();
        t.trainTo = r.trainTo();
        t.valFrom = r.valFrom();
        t.valTo = r.valTo();
        t.testFrom = r.testFrom();
        t.testTo = r.testTo();
        return t;
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
        return status == TrainingSetStatus.PENDING || status == TrainingSetStatus.BUILDING;
    }

    public void building() {
        this.status = TrainingSetStatus.BUILDING;
    }

    public void ready(long rowCount, List<String> columns, Map<String, Object> stats) {
        this.status = TrainingSetStatus.READY;
        this.rowCount = rowCount;
        this.columns = columns;
        this.columnCount = columns.size();
        this.stats = stats;
        this.message = null;
    }

    public void fail(String message) {
        this.status = TrainingSetStatus.FAILED;
        this.message = message;
    }

    /** 구간의 [시작, 끝) 시각. 날짜는 UTC 이고 끝 날짜 하루 전체를 포함한다. */
    public Instant[] range(Split split) {
        LocalDate from = switch (split) {
            case TRAIN -> trainFrom;
            case VAL -> valFrom;
            case TEST -> testFrom;
        };
        LocalDate to = switch (split) {
            case TRAIN -> trainTo;
            case VAL -> valTo;
            case TEST -> testTo;
        };
        return new Instant[]{from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()};
    }

    /** 학습 · 검증 · 최종 테스트. 파일의 split 값은 ordinal (0·1·2) 이다. */
    public enum Split {
        TRAIN("학습"), VAL("검증"), TEST("최종 테스트");

        private final String label;

        Split(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public String key() {
            return name().toLowerCase();
        }
    }
}
