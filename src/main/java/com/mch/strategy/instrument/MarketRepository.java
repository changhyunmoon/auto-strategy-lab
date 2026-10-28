package com.mch.strategy.instrument;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MarketRepository extends JpaRepository<Market, Long> {

    List<Market> findAllByOrderByIdAsc();

    /** 종목(asset)까지 함께 읽는다. 화면 · 백그라운드 작업에서 종목 코드를 바로 쓰기 위해서 */
    @Query("select m from Market m join fetch m.asset order by m.id")
    List<Market> findAllWithAsset();

    List<Market> findByAssetIdAndEnabledTrue(Long assetId);

    Optional<Market> findFirstByAssetIdAndExchangeAndEnabledTrue(Long assetId, Exchange exchange);
}
