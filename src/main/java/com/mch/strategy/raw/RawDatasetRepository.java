package com.mch.strategy.raw;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mch.strategy.common.BuildStatus;

public interface RawDatasetRepository extends JpaRepository<RawDataset, Long> {

    List<RawDataset> findAllByOrderByIdDesc();

    List<RawDataset> findByStatusOrderByIdDesc(BuildStatus status);

    List<RawDataset> findByStatusIn(List<BuildStatus> statuses);
}
