package com.mch.strategy.collect.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mch.strategy.instrument.Exchange;

import lombok.extern.slf4j.Slf4j;

/**
 * 업비트 분봉 REST: GET /v1/candles/minutes/1?market=KRW-BTC&to=...&count=200
 * to 이전(포함 안 함) 200개를 최신순으로 준다. 가장 오래된 봉 시각을 다음 to 로 넘겨 과거로 거슬러 간다.
 * 거래가 없던 분은 봉이 없다.
 */
@Slf4j
@Component
public class UpbitCandleSource implements CandleSource {

    private static final int PAGE_SIZE = 200;
    /** 업비트 시세 API 는 초당 10회 제한. 여유를 두고 초당 약 8회로 호출한다. */
    private static final long CALL_INTERVAL_MS = 125;
    private static final int MAX_RETRY = 10;

    private final RestClient client = RestClient.builder().baseUrl("https://api.upbit.com").build();
    private long lastCallAt = 0;

    @Override
    public Exchange exchange() {
        return Exchange.UPBIT;
    }

    @Override
    public void fetch(String symbol, Instant from, Instant to, Consumer<List<Candle1m>> batchConsumer) {
        Instant cursor = to;
        while (cursor.isAfter(from)) {
            List<UpbitCandle> page = callWithRetry(symbol, cursor);
            if (page.isEmpty()) {
                break; // 상장 이전 구간
            }
            List<Candle1m> batch = new ArrayList<>(page.size());
            Instant oldest = cursor;
            for (UpbitCandle c : page) {
                Instant t = c.openTime();
                if (t.isBefore(oldest)) {
                    oldest = t;
                }
                if (!t.isBefore(from) && t.isBefore(to)) {
                    batch.add(c.toCandle());
                }
            }
            batchConsumer.accept(batch);
            if (!oldest.isBefore(cursor)) {
                break; // 더 이상 과거로 가지 않음 (안전장치)
            }
            cursor = oldest;
        }
    }

    private List<UpbitCandle> callWithRetry(String symbol, Instant to) {
        for (int attempt = 1; ; attempt++) {
            throttle();
            try {
                UpbitCandle[] body = client.get()
                        .uri(b -> b.path("/v1/candles/minutes/1")
                                .queryParam("market", symbol)
                                .queryParam("to", to.toString())
                                .queryParam("count", PAGE_SIZE)
                                .build())
                        .retrieve()
                        .body(UpbitCandle[].class);
                return body == null ? List.of() : List.of(body);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS && attempt < MAX_RETRY) {
                    log.warn("업비트 호출 제한(429), {}초 후 다시 시도 ({}/{})", attempt, attempt, MAX_RETRY);
                    sleep(1000L * attempt);
                    continue;
                }
                throw e;
            }
        }
    }

    private synchronized void throttle() {
        long wait = lastCallAt + CALL_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집이 중단되었습니다.", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpbitCandle(
            @JsonProperty("candle_date_time_utc") String candleDateTimeUtc,
            @JsonProperty("opening_price") BigDecimal openingPrice,
            @JsonProperty("high_price") BigDecimal highPrice,
            @JsonProperty("low_price") BigDecimal lowPrice,
            @JsonProperty("trade_price") BigDecimal tradePrice,
            @JsonProperty("candle_acc_trade_price") BigDecimal accTradePrice,
            @JsonProperty("candle_acc_trade_volume") BigDecimal accTradeVolume
    ) {
        Instant openTime() {
            return LocalDateTime.parse(candleDateTimeUtc).toInstant(ZoneOffset.UTC);
        }

        Candle1m toCandle() {
            return new Candle1m(openTime(), openingPrice, highPrice, lowPrice, tradePrice,
                    accTradeVolume, accTradePrice, null, null, null);
        }
    }
}
