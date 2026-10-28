package com.mch.strategy.instrument;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mch.strategy.common.ApiException;

import lombok.RequiredArgsConstructor;

/** 거래쌍 정보를 종목 코드와 함께 꺼내 준다 (지연 로딩 없이 쓸 수 있는 값 객체). */
@Service
@RequiredArgsConstructor
public class MarketCatalog {

    private final MarketRepository marketRepository;

    public record MarketInfo(long id, long assetId, String assetCode, String assetName, Exchange exchange,
                             String symbol, String quoteCurrency, boolean enabled) {
        /** 예: "BTC · 업비트 KRW-BTC" */
        public String label() {
            return assetCode + " · " + exchange.getLabel() + " " + symbol;
        }
    }

    @Transactional(readOnly = true)
    public Map<Long, MarketInfo> all() {
        Map<Long, MarketInfo> out = new LinkedHashMap<>();
        for (Market m : marketRepository.findAllWithAsset()) {
            out.put(m.getId(), new MarketInfo(m.getId(), m.getAsset().getId(), m.getAsset().getCode(),
                    m.getAsset().getName(), m.getExchange(), m.getSymbol(), m.getQuoteCurrency(),
                    m.isEnabled() && m.getAsset().isEnabled()));
        }
        return out;
    }

    public MarketInfo get(long id) {
        MarketInfo m = all().get(id);
        if (m == null) {
            throw ApiException.notFound("거래쌍이 없습니다: " + id);
        }
        return m;
    }
}
