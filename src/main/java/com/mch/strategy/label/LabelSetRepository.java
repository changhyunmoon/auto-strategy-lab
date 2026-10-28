package com.mch.strategy.label;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mch.strategy.common.BuildStatus;

public interface LabelSetRepository extends JpaRepository<LabelSet, Long> {

    List<LabelSet> findAllByOrderByIdDesc();

    List<LabelSet> findByStatusOrderByIdDesc(BuildStatus status);

    List<LabelSet> findByFeatureSetId(Long featureSetId);
}
