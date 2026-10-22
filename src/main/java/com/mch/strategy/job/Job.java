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

    public static final String TYPE_DATASET_BUILD = "DATASET_BUILD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_type", nullable = false, length = 30)
    private String jobType;

    @Column(name = "dataset_id")
    private Long datasetId;

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

    public static Job datasetBuild(long datasetId) {
        Job job = new Job();
        job.jobType = TYPE_DATASET_BUILD;
        job.datasetId = datasetId;
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
        this.finishedAt = Instant.now();
    }

    public void fail(String message) {
        this.status = JobStatus.FAILED;
        this.message = message;
        this.finishedAt = Instant.now();
    }
}
