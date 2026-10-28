package com.mch.strategy.job;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 백그라운드 작업 하나의 진행 상태. 화면이 2초마다 조회해 진행률을 그린다. */
@Entity
@Table(name = "job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    /** V2: 원본 1분봉 받기 + 입력 검사 */
    public static final String TYPE_RAW_BUILD = "V2_RAW_BUILD";
    /** V2: 기술 지표 계산 */
    public static final String TYPE_FEATURE_BUILD = "V2_FEATURE_BUILD";
    /** V2: H·P·L 정답 계산 */
    public static final String TYPE_LABEL_BUILD = "V2_LABEL_BUILD";
    /** V2: 학습·검증·최종 테스트 분할 */
    public static final String TYPE_SPLIT_BUILD = "V2_SPLIT_BUILD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_type", nullable = false, length = 30)
    private String jobType;

    @Column(name = "dataset_id")
    private Long datasetId;

    /** 데이터세트가 아닌 작업의 대상 (학습 데이터 id · 실험 id) */
    @Column(name = "target_id")
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false)
    private int progress;

    @Column(length = 50)
    private String step;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** V2 작업: 대상(원본 · 지표 · 정답 · 분할)의 id 를 target_id 에 둔다. dataset_id 는 V1 에서만 쓰던 열이다. */
    public static Job forTarget(String type, long targetId) {
        Job job = new Job();
        job.jobType = type;
        job.targetId = targetId;
        job.status = JobStatus.RUNNING;
        job.step = "대기";
        job.createdAt = Instant.now();
        return job;
    }

    public void start() {
        this.startedAt = Instant.now();
    }

    public void update(int progress, String step, String message) {
        this.progress = Math.max(0, Math.min(100, progress));
        this.step = step;
        this.message = message;
    }

    public void succeed() {
        this.status = JobStatus.SUCCEEDED;
        this.progress = 100;
        this.step = "완료";
        this.message = null;
        this.finishedAt = Instant.now();
    }

    public void fail(String message) {
        this.status = JobStatus.FAILED;
        this.message = message;
        this.finishedAt = Instant.now();
    }
}
