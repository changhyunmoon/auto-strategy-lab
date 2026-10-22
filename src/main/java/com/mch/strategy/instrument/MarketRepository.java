package com.mch.strategy.instrument;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketRepository extends JpaRepository<Market, Long> {

    List<Market> findAllByOrderByIdAsc();

    List<Market> findByAssetIdAndEnabledTrue(Long assetId);

    Optional<Market> findFirstByAssetIdAndExchangeAndEnabledTrue(Long assetId, Exchange exchange);
}
