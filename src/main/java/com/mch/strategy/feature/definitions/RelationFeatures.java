package com.mch.strategy.feature.definitions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mch.strategy.feature.Feature;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureRequirement;
import com.mch.strategy.feature.Ta;

import static com.mch.strategy.feature.FeatureGroup.F_RELATION;

/** F. 자산·거래소 간 관계. 수익률끼리 비교하므로 환율이 필요 없다. */
@Configuration
public class RelationFeatures {

    @Bean
    FeatureDefinition relativeReturn() {
        return Feature.of("rel_return", "BTC·ETH 상대 수익률", F_RELATION)
                .param("5·15·60봉 · 이 자산 − 다른 자산").warmupBars(60)
                .requires(FeatureRequirement.BOTH_ASSETS)
                .columns("rel_return_5", "rel_return_15", "rel_return_60")
                .compute(in -> new double[][]{
                        Ta.subtract(Ta.logReturn(in.close(), 5), Ta.logReturn(in.otherClose(), 5)),
                        Ta.subtract(Ta.logReturn(in.close(), 15), Ta.logReturn(in.otherClose(), 15)),
                        Ta.subtract(Ta.logReturn(in.close(), 60), Ta.logReturn(in.otherClose(), 60))});
    }

    @Bean
    FeatureDefinition assetCorrelation() {
        return Feature.of("asset_corr", "BTC·ETH 이동 상관", F_RELATION)
                .param("60봉 · 1봉 수익률 상관").warmupBars(61)
                .requires(FeatureRequirement.BOTH_ASSETS)
                .columns("asset_corr_60")
                .compute(in -> new double[][]{
                        Ta.rollingCorr(Ta.logReturn(in.close(), 1), Ta.logReturn(in.otherClose(), 1), 60)});
    }

    @Bean
    FeatureDefinition binanceLeadReturn() {
        return Feature.of("binance_return", "바이낸스 선행 수익률", F_RELATION)
                .param("직전 1·5봉 · 같은 자산의 바이낸스 수익률").warmupBars(5)
                .requires(FeatureRequirement.BOTH_EXCHANGES)
                .columns("binance_return_1", "binance_return_5")
                .compute(in -> new double[][]{Ta.logReturn(in.refClose(), 1), Ta.logReturn(in.refClose(), 5)});
    }
}
