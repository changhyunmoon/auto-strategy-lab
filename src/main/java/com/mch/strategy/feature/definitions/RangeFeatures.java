package com.mch.strategy.feature.definitions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mch.strategy.feature.Feature;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.Ta;

import static com.mch.strategy.feature.FeatureGroup.C_RANGE;

/** C. 범위 안의 위치·강도 */
@Configuration
public class RangeFeatures {

    @Bean
    FeatureDefinition rsi() {
        return Feature.of("rsi", "RSI", C_RANGE)
                .param("14 · 0~1로 표시").warmupBars(42)
                .columns("rsi_14")
                .compute(in -> {
                    double[] rsi = Ta.rsi(in.close(), 14);
                    for (int i = 0; i < rsi.length; i++) rsi[i] /= 100;
                    return new double[][]{rsi};
                });
    }

    @Bean
    FeatureDefinition bollingerPercentB() {
        return Feature.of("bb_pctb", "볼린저 %B", C_RANGE)
                .param("20, 2").warmupBars(20)
                .columns("bb_pctb_20")
                .compute(in -> {
                    double[] mid = Ta.sma(in.close(), 20), sd = Ta.rollingStd(in.close(), 20);
                    double[] out = Ta.nan(mid.length);
                    for (int i = 0; i < out.length; i++) {
                        if (sd[i] > 0) out[i] = (in.close()[i] - (mid[i] - 2 * sd[i])) / (4 * sd[i]);
                    }
                    return new double[][]{out};
                });
    }

    @Bean
    FeatureDefinition donchianPosition() {
        return Feature.of("donchian_pos", "돈치안 위치", C_RANGE)
                .param("20 · 최근 최저 0 ~ 최고 1").warmupBars(20)
                .columns("donchian_pos_20")
                .compute(in -> {
                    double[] hi = Ta.rollingMax(in.high(), 20), lo = Ta.rollingMin(in.low(), 20);
                    double[] out = Ta.nan(hi.length);
                    for (int i = 0; i < out.length; i++) {
                        double range = hi[i] - lo[i];
                        if (range > 0) out[i] = (in.close()[i] - lo[i]) / range;
                    }
                    return new double[][]{out};
                });
    }
}
