package com.mch.strategy.collect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.mch.strategy.collect.source.CandleSource;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.Market;

import lombok.extern.slf4j.Slf4j;

/**
 * 마켓 하나의 [from, to) 구간을 받아 candle_1m 에 저장한다.
 * - 이전에 성공한 수집 이력이 이 구간을 모두 덮으면 건너뛴다.
 * - 아니면 구간 전체를 다시 받고, 이미 있는 봉은 INSERT IGNORE 로 건너뛴다.
 * - 중간에 실패하면 이어받지 않는다. 다시 실행하면 처음부터 받는다.
 */
@Slf4j
@Service
public class CollectService {

    private final Map<Exchange, CandleSource> sources;
    private final CandleRepository candleRepository;
    private final CollectLogRepository collectLogRepository;

    public CollectService(List<CandleSource> sources, CandleRepository candleRepository,
                          CollectLogRepository collectLogRepository) {
        this.sources = sources.stream().collect(Collectors.toMap(CandleSource::exchange, Function.identity()));
        this.candleRepository = candleRepository;
        this.collectLogRepository = collectLogRepository;
    }

    /**
     * @param onRows 지금까지 받은 봉 개수를 알려준다 (진행률 표시용)
     * @return 받은 봉 개수. 건너뛰었으면 -1
     */
    public int collect(Market market, Instant from, Instant to, IntConsumer onRows) {
        if (alreadyCollected(market.getId(), from, to)) {
            log.info("{} {} 이미 받은 구간이라 건너뜀: {} ~ {}", market.getExchange(), market.getSymbol(), from, to);
            return -1;
        }
        CandleSource source = sources.get(market.getExchange());
        if (source == null) {
            throw new IllegalStateException(market.getExchange() + " 수집기가 없습니다.");
        }

        CollectLog collectLog = collectLogRepository.save(CollectLog.start(market.getId(), from, to));
        AtomicInteger count = new AtomicInteger();
        try {
            source.fetch(market.getSymbol(), from, to, batch -> {
                candleRepository.insertIgnore(market.getId(), batch);
                onRows.accept(count.addAndGet(batch.size()));
            });
            collectLog.succeed(count.get());
            collectLogRepository.save(collectLog);
            log.info("{} {} 수집 완료: {}개", market.getExchange(), market.getSymbol(), count.get());
            return count.get();
        } catch (RuntimeException e) {
            collectLog.fail(count.get(), e.getMessage());
            collectLogRepository.save(collectLog);
            throw e;
        }
    }

    /** 성공한 이력들의 구간을 이어 붙였을 때 [from, to) 를 빈틈없이 덮는지. */
    public boolean alreadyCollected(long marketId, Instant from, Instant to) {
        return missingRanges(marketId, from, to).isEmpty();
    }

    /** 성공한 수집 이력을 이어 붙인 구간들 (시간순, 겹치거나 맞닿으면 하나로) */
    public List<Range> collectedRanges(long marketId) {
        List<Range> out = new ArrayList<>();
        for (CollectLog log : collectLogRepository.findByMarketIdAndStatusOrderByRangeFromAsc(marketId, CollectLog.SUCCESS)) {
            Range last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && !log.getRangeFrom().isAfter(last.to())) {
                if (log.getRangeTo().isAfter(last.to())) {
                    out.set(out.size() - 1, new Range(last.from(), log.getRangeTo()));
                }
            } else {
                out.add(new Range(log.getRangeFrom(), log.getRangeTo()));
            }
        }
        return out;
    }

    /** [from, to) 중 아직 받지 않은 구간들. 비어 있으면 모두 받은 것이다 */
    public List<Range> missingRanges(long marketId, Instant from, Instant to) {
        List<Range> missing = new ArrayList<>();
        Instant cursor = from;
        for (Range r : collectedRanges(marketId)) {
            if (!r.to().isAfter(cursor)) {
                continue;
            }
            if (!r.from().isBefore(to)) {
                break;
            }
            if (r.from().isAfter(cursor)) {
                missing.add(new Range(cursor, r.from()));
            }
            cursor = r.to();
            if (!cursor.isBefore(to)) {
                return missing;
            }
        }
        missing.add(new Range(cursor, to));
        return missing;
    }

    /** [from, to) 시간 구간 */
    public record Range(Instant from, Instant to) {
    }
}
