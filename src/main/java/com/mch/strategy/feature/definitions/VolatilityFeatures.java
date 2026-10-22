package com.mch.strategy.feature.definitions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mch.strategy.feature.Feature;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.Ta;

import static com.mch.strategy.feature.FeatureGroup.D_VOLATILITY;

/** D. 변동성 */
@Configuration
public class VolatilityFeatures {

    @Bean
    FeatureDefinition normalizedAtr() {
        return Feature.of("natr", "정규화 ATR", D_VOLATILITY)
                .param("14 · ATR ÷ 종가").warmupBars(42)
                .columns("natr_14")
                .compute(in -> new double[][]{Ta.divide(Ta.atr(in.high(), in.low(), in.close(), 14), in.close())});
    }

    @Bean
    FeatureDefinition realizedVolatility() {
        return Feature.of("realized_vol", "실현 변동성", D_VOLATILITY)
                .param("15, 60 · 1봉 로그 수익률의 표준편차").warmupBars(61)
                .columns("realized_vol_15", "realized_vol_60")
                .compute(in -> {
                    double[] r = Ta.logReturn(in.close(), 1);
                    return new double[][]{Ta.rollingStd(r, 15), Ta.rollingStd(r, 60)};
                });
    }

    @Bean
    FeatureDefinition bollingerWidth() {
        return Feature.of("bb_width", "볼린저 밴드 폭", D_VOLATILITY)
                .param("20 · (상단 − 하단) ÷ 중심선").warmupBars(20)
                .columns("bb_width_20")
                .compute(in -> new double[][]{
                        Ta.divide(timesFour(Ta.rollingStd(in.close(), 20)), Ta.sma(in.close(), 20))});
    }

    @Bean
    FeatureDefinition highLowRange() {
        return Feature.of("hl_range", "봉 고저 폭", D_VOLATILITY)
                .param("(고가 − 저가) ÷ 종가")
                .columns("hl_range")
                .compute(in -> new double[][]{Ta.divide(Ta.subtract(in.high(), in.low()), in.close())});
    }

    private static double[] timesFour(double[] x) {
        double[] out = new double[x.length];
        for (int i = 0; i < x.length; i++) out[i] = x[i] * 4;
        return out;
    }
}
