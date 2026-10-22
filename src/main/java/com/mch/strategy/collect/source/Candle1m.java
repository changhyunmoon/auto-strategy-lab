package com.mch.strategy.collect.source;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 거래소에서 받은 1분봉 한 개.
 * tradeCount · takerBuy 값은 바이낸스만 제공하므로 업비트는 null.
 */
public record Candle1m(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal quoteVolume,
        Integer tradeCount,
        BigDecimal takerBuyVolume,
        BigDecimal takerBuyQuote
) {
}
