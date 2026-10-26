package com.mch.strategy.training;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelRunRepository extends JpaRepository<ModelRun, Long> {

    List<ModelRun> findAllByOrderByIdDesc();

    List<ModelRun> findByTrainingSetIdOrderByIdDesc(Long trainingSetId);

    List<ModelRun> findByStatusIn(Collection<ModelRunStatus> statuses);

    List<ModelRun> findByTestStatus(ModelRun.TestStatus status);

    long countByTrainingSetId(Long trainingSetId);
}
