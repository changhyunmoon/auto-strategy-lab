package com.mch.strategy.feature.definitions;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mch.strategy.feature.Feature;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureRequirement;
import com.mch.strategy.feature.Ta;

import static com.mch.strategy.feature.FeatureGroup.E_VOLUME;

/** E. 거래량·체결 방향 */
@Configuration
public class VolumeFeatures {

    @Bean
    FeatureDefinition volumeRatio() {
        return Feature.of("volume_ratio", "거래량 비율", E_VOLUME)
                .param("20, 60 · log(거래량 ÷ 최근 평균)").warmupBars(60)
                .columns("volume_ratio_20", "volume_ratio_60")
                .compute(in -> new double[][]{logRatio(in.volume(), 20), logRatio(in.volume(), 60)});
    }

    @Bean
    FeatureDefinition takerBuyRatio() {
        return Feature.of("taker_buy_ratio", "매수 주도 거래량 비율", E_VOLUME)
                .param("바이낸스 taker buy ÷ 거래량")
                .requires(FeatureRequirement.BINANCE_CANDLES)
                .columns("taker_buy_ratio")
                .compute(in -> new double[][]{Ta.divide(in.refTakerBuy(), in.refVolume())});
    }

    @Bean
    FeatureDefinition averageTradeSizeRatio() {
        return Feature.of("avg_trade_size_ratio", "평균 체결 크기 비율", E_VOLUME)
                .param("바이낸스 (거래량 ÷ 체결 건수) ÷ 최근 20봉 평균").warmupBars(20)
                .requires(FeatureRequirement.BINANCE_CANDLES)
                .columns("avg_trade_size_ratio")
                .compute(in -> {
                    double[] size = Ta.divide(in.refVolume(), in.refTradeCount());
                    return new double[][]{Ta.divide(size, Ta.sma(size, 20))};
                });
    }

    @Bean
    FeatureDefinition candleShape() {
        return Feature.of("candle_shape", "캔들 모양", E_VOLUME)
                .param("몸통 · 윗꼬리 · 아랫꼬리 ÷ 고저 폭")
                .columns("candle_body", "candle_upper", "candle_lower")
                .compute(in -> {
                    int n = in.size();
                    double[] body = new double[n], upper = new double[n], lower = new double[n];
                    for (int i = 0; i < n; i++) {
                        double o = in.open()[i], c = in.close()[i], h = in.high()[i], l = in.low()[i];
                        double range = h - l;
                        if (range <= 0) continue; // 모두 0
                        body[i] = (c - o) / range;
                        upper[i] = (h - Math.max(o, c)) / range;
                        lower[i] = (Math.min(o, c) - l) / range;
                    }
                    return new double[][]{body, upper, lower};
                });
    }

    @Bean
    FeatureDefinition signedVolume() {
        return Feature.of("signed_volume", "방향 붙인 거래량", E_VOLUME)
                .param("20 · 상승 봉 +, 하락 봉 − 거래량 비율의 최근 20봉 평균").warmupBars(40)
                .columns("signed_volume_20")
                .compute(in -> {
                    double[] avg = Ta.sma(in.volume(), 20);
                    double[] signed = Ta.nan(in.size());
                    for (int i = 0; i < signed.length; i++) {
                        if (avg[i] > 0) signed[i] = Math.signum(in.close()[i] - in.open()[i]) * in.volume()[i] / avg[i];
                    }
                    return new double[][]{Ta.sma(signed, 20)};
                });
    }

    @Bean
    FeatureDefinition turnoverZ() {
        return Feature.of("turnover_z", "거래대금 z점수", E_VOLUME)
                .param("최근 24시간 · 로그 거래대금 기준").warmupMinutes(1440)
                .columns("turnover_z_24h")
                .compute(in -> {
                    int window = Math.max(2, 1440 / in.intervalMinutes());
                    double[] logTurnover = Ta.nan(in.size());
                    for (int i = 0; i < logTurnover.length; i++) {
                        if (in.quoteVolume()[i] > 0) logTurnover[i] = Math.log(in.quoteVolume()[i]);
                    }
                    double[] mean = Ta.sma(logTurnover, window), sd = Ta.rollingStd(logTurnover, window);
                    double[] z = Ta.nan(logTurnover.length);
                    for (int i = 0; i < z.length; i++) {
                        if (sd[i] > 0) z[i] = (logTurnover[i] - mean[i]) / sd[i];
                    }
                    return new double[][]{z};
                });
    }

    @Bean
    FeatureDefinition upbitVolumeShare() {
        return Feature.of("upbit_volume_share", "업비트 거래량 비중", E_VOLUME)
                .param("log(업비트 ÷ 바이낸스 거래량) · 최근 60봉 평균 대비 변화").warmupBars(60)
                .requires(FeatureRequirement.BOTH_EXCHANGES)
                .columns("upbit_vol_share", "upbit_vol_share_chg")
                .compute(in -> {
                    double[] share = Ta.nan(in.size());
                    for (int i = 0; i < share.length; i++) {
                        if (in.volume()[i] > 0 && in.refVolume()[i] > 0) share[i] = Math.log(in.volume()[i] / in.refVolume()[i]);
                    }
                    return new double[][]{share, Ta.subtract(share, Ta.sma(share, 60))};
                });
    }

    /** log(x ÷ 최근 n 개 평균). x 가 0 이면 NaN. */
    private static double[] logRatio(double[] x, int n) {
        double[] avg = Ta.sma(x, n);
        double[] out = Ta.nan(x.length);
        for (int i = 0; i < x.length; i++) {
            if (x[i] > 0 && avg[i] > 0) out[i] = Math.log(x[i] / avg[i]);
        }
        return out;
    }
}
