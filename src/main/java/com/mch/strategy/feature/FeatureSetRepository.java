package com.mch.strategy.feature;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mch.strategy.common.BuildStatus;

public interface FeatureSetRepository extends JpaRepository<FeatureSet, Long> {

    List<FeatureSet> findAllByOrderByIdDesc();

    List<FeatureSet> findByStatusOrderByIdDesc(BuildStatus status);

    List<FeatureSet> findByRawDatasetId(Long rawDatasetId);
}
