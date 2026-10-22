package com.mch.strategy.job;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByStatus(JobStatus status);

    Optional<Job> findFirstByDatasetIdOrderByIdDesc(Long datasetId);
}
