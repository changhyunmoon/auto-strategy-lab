package com.mch.strategy.collect;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectLogRepository extends JpaRepository<CollectLog, Long> {

    List<CollectLog> findByMarketIdAndStatusOrderByRangeFromAsc(Long marketId, String status);
}
