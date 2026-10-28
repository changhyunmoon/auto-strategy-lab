package com.mch.strategy.feature;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FeaturePresetRepository extends JpaRepository<FeaturePreset, Long> {

    List<FeaturePreset> findAllByOrderByIdDesc();
}
