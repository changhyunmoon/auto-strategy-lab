package com.mch.strategy.dataset;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasetRepository extends JpaRepository<Dataset, Long> {

    List<Dataset> findAllByOrderByIdDesc();

    List<Dataset> findByStatusNotIn(Collection<DatasetStatus> statuses);

    List<Dataset> findByStatusOrderByIdDesc(DatasetStatus status);
}
