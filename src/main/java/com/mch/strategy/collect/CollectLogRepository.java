package com.mch.strategy.collect;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CollectLogRepository extends JpaRepository<CollectLog, Long> {

    List<CollectLog> findByMarketIdAndStatusOrderByRangeFromAsc(Long marketId, String status);

    List<CollectLog> findByStatus(String status);

    /** 원본 데이터 받기 화면의 최근 이력 */
    List<CollectLog> findTop50ByOrderByIdDesc();
}
