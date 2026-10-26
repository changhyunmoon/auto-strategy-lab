package com.mch.strategy.trainset;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainingSetRepository extends JpaRepository<TrainingSet, Long> {

    List<TrainingSet> findAllByOrderByIdDesc();

    List<TrainingSet> findByStatusOrderByIdDesc(TrainingSetStatus status);

    List<TrainingSet> findByStatusIn(Collection<TrainingSetStatus> statuses);
}
