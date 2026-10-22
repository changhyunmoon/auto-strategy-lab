package com.mch.strategy.feature.definitions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mch.strategy.feature.Feature;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureInput;
import com.mch.strategy.feature.Ta;

import static com.mch.strategy.feature.FeatureGroup.A_RETURN;

/** A. 수익률·모멘텀 */
@Configuration
public class ReturnFeatures {

    @Bean
    FeatureDefinition logReturn() {
        return Feature.of("log_return", "과거 로그 수익률", A_RETURN)
                .param("1·5·15·60봉").warmupBars(60)
                .columns("log_return_1", "log_return_5", "log_return_15", "log_return_60")
                .compute(in -> new double[][]{
                        Ta.logReturn(in.close(), 1), Ta.logReturn(in.close(), 5),
                        Ta.logReturn(in.close(), 15), Ta.logReturn(in.close(), 60)});
    }

    @Bean
    FeatureDefinition macdHist() {
        return Feature.of("macd_hist", "MACD 히스토그램", A_RETURN)
                .param("12, 26, 9 · 종가로 정규화").warmupBars(70)
                .columns("macd_hist")
                .compute(in -> {
                    Macd m = Macd.of(in);
                    return new double[][]{Ta.divide(Ta.subtract(m.line, m.signal), in.close())};
                });
    }

    @Bean
    FeatureDefinition macdLineSignal() {
        return Feature.of("macd_line_signal", "MACD선·신호선", A_RETURN)
                .param("12, 26, 9 · 종가로 정규화").warmupBars(70)
                .columns("macd_line", "macd_signal")
                .compute(in -> {
                    Macd m = Macd.of(in);
                    return new double[][]{Ta.divide(m.line, in.close()), Ta.divide(m.signal, in.close())};
                });
    }

    @Bean
    FeatureDefinition macdCross() {
        return Feature.of("macd_cross", "MACD 교차 상태", A_RETURN)
                .param("방향 ±1 · 경과 봉 수").warmupBars(70)
                .columns("macd_cross_dir", "macd_cross_age")
                .compute(in -> {
                    Macd m = Macd.of(in);
                    return Ta.crossState(Ta.subtract(m.line, m.signal));
                });
    }

    /** MACD(12, 26, 9) 원값. 여러 지표가 같이 쓴다. */
    record Macd(double[] line, double[] signal) {
        static Macd of(FeatureInput in) {
            double[] line = Ta.subtract(Ta.ema(in.close(), 12), Ta.ema(in.close(), 26));
            return new Macd(line, Ta.ema(line, 9));
        }
    }
}
