package com.mch.strategy.label;

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
 * H · P · L 정답 데이터셋: 지표 데이터셋의 모든 1분봉 행에 정답 상태를 붙인 것 (정답 정의 하나 = 한 버전).
 * 값은 v2/labels/{id}/ 파일에 있다. exploreTo 이후 행의 정답 분포는 탐색 중 보여주지 않는다.
 */
@Entity
@Table(name = "v2_label_set")
@Getter
@Setter
@NoArgsConstructor
public class LabelSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BuildStatus status;

    @Column(name = "feature_set_id", nullable = false)
    private Long featureSetId;

    /** LabelConfig JSON */
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String config;

    @Column(name = "explore_to", nullable = false)
    private Instant exploreTo;

    @Column(name = "code_version", nullable = false, length = 30)
    private String codeVersion;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    /** LabelSummary JSON */
    @Column(columnDefinition = "LONGTEXT")
    private String summary;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
