package com.mch.strategy.collect.source;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import com.mch.strategy.instrument.Exchange;

/**
 * 거래소별 1분봉 수집기. 새 출처를 붙일 때는 이 인터페이스를 구현한 빈 하나를 추가한다.
 */
public interface CandleSource {

    Exchange exchange();

    /**
     * [from, to) 구간의 1분봉을 받아 묶음 단위로 넘긴다.
     * 받는 즉시 저장할 수 있도록 전체를 메모리에 모으지 않고 batchConsumer 로 전달한다.
     * 묶음 안의 순서와 묶음 간 순서는 보장하지 않는다.
     */
    void fetch(String symbol, Instant from, Instant to, Consumer<List<Candle1m>> batchConsumer);
}
