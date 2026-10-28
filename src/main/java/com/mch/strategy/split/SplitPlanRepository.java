package com.mch.strategy.split;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SplitPlanRepository extends JpaRepository<SplitPlan, Long> {

    List<SplitPlan> findAllByOrderByIdDesc();

    List<SplitPlan> findByLabelSetId(Long labelSetId);
}
