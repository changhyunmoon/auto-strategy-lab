package com.mch.strategy.dataset;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DatasetColumnRepository extends JpaRepository<DatasetColumn, Long> {

    List<DatasetColumn> findByDatasetIdOrderByColumnIndexAsc(Long datasetId);

    @Modifying
    @Query("delete from DatasetColumn c where c.datasetId = :datasetId")
    void deleteByDatasetId(@Param("datasetId") Long datasetId);
}
