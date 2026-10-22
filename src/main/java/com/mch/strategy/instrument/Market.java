package com.mch.strategy.instrument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 거래소별 거래쌍. 예: 업비트 KRW-BTC, 바이낸스 BTCUSDT. */
@Entity
@Table(name = "market")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(name = "quote_currency", nullable = false, length = 10)
    private String quoteCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketRole role;

    @Column(nullable = false)
    private boolean enabled;

    public void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
