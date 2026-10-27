package com.mch.strategy.job;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByStatus(JobStatus status);

    Optional<Job> findFirstByDatasetIdOrderByIdDesc(Long datasetId);

    Optional<Job> findFirstByJobTypeAndTargetIdOrderByIdDesc(String jobType, Long targetId);

    Optional<Job> findFirstByJobTypeAndStatusOrderByIdDesc(String jobType, JobStatus status);

    /** 대기 순서: 이 작업보다 먼저 만들어져 아직 끝나지 않은 작업 수 */
    long countByStatusAndIdLessThan(JobStatus status, Long id);
}
