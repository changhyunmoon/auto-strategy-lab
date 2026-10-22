package com.mch.strategy.instrument;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByEnabledTrueOrderByIdAsc();
}
