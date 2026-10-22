package com.mch.strategy.feature.definitions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mch.strategy.feature.Feature;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.Ta;

import static com.mch.strategy.feature.FeatureGroup.B_TREND;

/** B. 추세·평균 대비 위치. EMA 는 안정되도록 기간의 2배를 워밍업으로 둔다. */
@Configuration
public class TrendFeatures {

    @Bean
    FeatureDefinition emaDeviation() {
        return Feature.of("ema_dev", "EMA 괴리", B_TREND)
                .param("20, 60 · 종가 ÷ EMA − 1").warmupBars(120)
                .columns("ema_dev_20", "ema_dev_60")
                .compute(in -> new double[][]{
                        Ta.ratioMinusOne(in.close(), Ta.ema(in.close(), 20)),
                        Ta.ratioMinusOne(in.close(), Ta.ema(in.close(), 60))});
    }

    @Bean
    FeatureDefinition emaSlope() {
        return Feature.of("ema_slope", "EMA 기울기", B_TREND)
                .param("EMA 20의 최근 5봉 변화율").warmupBars(45)
                .columns("ema_slope_20")
                .compute(in -> {
                    double[] ema = Ta.ema(in.close(), 20);
                    double[] out = Ta.nan(ema.length);
                    for (int i = 5; i < ema.length; i++) {
                        out[i] = ema[i] / ema[i - 5] - 1;
                    }
                    return new double[][]{out};
                });
    }

    @Bean
    FeatureDefinition emaCross() {
        return Feature.of("ema_cross", "골든·데드크로스", B_TREND)
                .param("EMA 20 / 60 · 골든 +1 · 데드 −1 · 경과 봉 수").warmupBars(120)
                .columns("ema_cross_dir", "ema_cross_age")
                .compute(in -> Ta.crossState(Ta.subtract(Ta.ema(in.close(), 20), Ta.ema(in.close(), 60))));
    }

    @Bean
    FeatureDefinition emaAlignment() {
        return Feature.of("ema_alignment", "이동평균 배열", B_TREND)
                .param("EMA 20 / 60 / 120 · 정배열 +1 · 역배열 −1").warmupBars(240)
                .columns("ema_alignment")
                .compute(in -> {
                    double[] e20 = Ta.ema(in.close(), 20), e60 = Ta.ema(in.close(), 60), e120 = Ta.ema(in.close(), 120);
                    double[] out = Ta.nan(e20.length);
                    for (int i = 0; i < out.length; i++) {
                        if (Double.isNaN(e120[i])) continue;
                        out[i] = e20[i] > e60[i] && e60[i] > e120[i] ? 1
                                : e20[i] < e60[i] && e60[i] < e120[i] ? -1 : 0;
                    }
                    return new double[][]{out};
                });
    }

    @Bean
    FeatureDefinition vwapDeviation() {
        return Feature.of("vwap_dev", "VWAP 괴리", B_TREND)
                .param("이동 60봉").warmupBars(60)
                .columns("vwap_dev_60")
                .compute(in -> {
                    int n = in.size();
                    double[] pv = new double[n];
                    for (int i = 0; i < n; i++) {
                        double typical = (in.high()[i] + in.low()[i] + in.close()[i]) / 3;
                        pv[i] = typical * in.volume()[i];
                    }
                    double[] vwap = Ta.divide(Ta.rollingSum(pv, 60), Ta.rollingSum(in.volume(), 60));
                    return new double[][]{Ta.ratioMinusOne(in.close(), vwap)};
                });
    }
}
