package com.mch.strategy.raw;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.instrument.Exchange;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 원본 데이터셋: 한 거래소의 BTC·ETH 거래쌍 완료 1분봉 [rangeFrom, rangeTo).
 * 1분봉 값은 candle_1m 에 있고 (같은 봉은 다시 받지 않으므로 값이 바뀌지 않는다) 여기에는 범위 · 입력 검사 결과를 둔다.
 */
@Entity
@Table(name = "v2_raw_dataset")
@Getter
@Setter
@NoArgsConstructor
public class RawDataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BuildStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Exchange exchange;

    @Column(name = "market_ids", nullable = false, length = 200)
    private String marketIds;

    @Column(name = "range_from", nullable = false)
    private Instant rangeFrom;

    @Column(name = "range_to", nullable = false)
    private Instant rangeTo;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    /** 거래쌍별 입력 검사 결과 JSON (List&lt;RawQuality.MarketQuality&gt;) */
    @Column(columnDefinition = "LONGTEXT")
    private String quality;

    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public List<Long> marketIdList() {
        return Arrays.stream(marketIds.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).toList();
    }

    public void setMarketIdList(List<Long> ids) {
        this.marketIds = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
