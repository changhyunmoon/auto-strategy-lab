package com.mch.strategy.dataset;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DatasetQualityRepository extends JpaRepository<DatasetQuality, Long> {

    List<DatasetQuality> findByDatasetIdOrderByMarketIdAsc(Long datasetId);

    @Modifying
    @Query("delete from DatasetQuality q where q.datasetId = :datasetId")
    void deleteByDatasetId(@Param("datasetId") Long datasetId);
}
