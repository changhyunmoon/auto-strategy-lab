package com.mch.strategy.feature.definitions;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mch.strategy.feature.Feature;
import com.mch.strategy.feature.FeatureDefinition;

import static com.mch.strategy.feature.FeatureGroup.G_TIME;

/** G. 시간 (UTC 기준) */
@Configuration
public class TimeFeatures {

    @Bean
    FeatureDefinition timeOfDay() {
        return Feature.of("time_of_day", "시각 (sin·cos)", G_TIME)
                .param("UTC 시·분을 하루 주기로 변환")
                .columns("tod_sin", "tod_cos")
                .compute(in -> {
                    int n = in.size();
                    double[] sin = new double[n], cos = new double[n];
                    for (int i = 0; i < n; i++) {
                        double minuteOfDay = Math.floorMod(in.time()[i] / 60_000L, 1440L);
                        double angle = 2 * Math.PI * minuteOfDay / 1440;
                        sin[i] = Math.sin(angle);
                        cos[i] = Math.cos(angle);
                    }
                    return new double[][]{sin, cos};
                });
    }

    @Bean
    FeatureDefinition dayOfWeek() {
        return Feature.of("day_of_week", "요일·주말 여부", G_TIME)
                .param("UTC 기준 · 월 0 ~ 일 6")
                .columns("day_of_week", "is_weekend")
                .compute(in -> {
                    int n = in.size();
                    double[] dow = new double[n], weekend = new double[n];
                    for (int i = 0; i < n; i++) {
                        DayOfWeek d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(in.time()[i]), ZoneOffset.UTC).getDayOfWeek();
                        dow[i] = d.getValue() - 1;
                        weekend[i] = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY ? 1 : 0;
                    }
                    return new double[][]{dow, weekend};
                });
    }
}
