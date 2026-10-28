package com.mch.strategy.raw;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.collect.CandleSeries;
import com.mch.strategy.collect.CollectService;
import com.mch.strategy.common.AsyncConfigNames;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.Json;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketCatalog;
import com.mch.strategy.instrument.MarketCatalog.MarketInfo;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.JobCancels;
import com.mch.strategy.job.JobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 백그라운드: 거래쌍마다 1분봉을 받고(이미 받은 기간은 건너뜀) 입력 검사를 한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RawBuilder {

    private final RawDatasetRepository repository;
    private final CollectService collectService;
    private final CandleRepository candleRepository;
    private final MarketRepository marketRepository;
    private final MarketCatalog marketCatalog;
    private final JobService jobService;
    private final JobCancels jobCancels;

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void run(long id, long jobId) {
        RawDataset d = repository.findById(id).orElseThrow();
        try {
            jobService.start(jobId);
            d.setStatus(BuildStatus.BUILDING);
            d = repository.save(d);
            List<Long> marketIds = d.marketIdList();
            long totalMinutes = (d.getRangeTo().toEpochMilli() - d.getRangeFrom().toEpochMilli()) / 60_000L;
            List<RawQuality.MarketQuality> qualities = new ArrayList<>();
            long rows = 0;
            for (int k = 0; k < marketIds.size(); k++) {
                long marketId = marketIds.get(k);
                Market market = marketRepository.findById(marketId).orElseThrow();
                MarketInfo info = marketCatalog.get(marketId);
                int base = k * 90 / marketIds.size();
                int span = 90 / marketIds.size();
                jobService.update(jobId, base, "원본 수집", info.label() + " 1분봉 받는 중");
                collectService.collect(market, d.getRangeFrom(), d.getRangeTo(), count -> {
                    jobCancels.check(jobId);
                    int pct = base + (int) Math.min(span * 0.8, span * 0.8 * count / Math.max(1, totalMinutes));
                    jobService.update(jobId, pct, "원본 수집", info.label() + " " + count + " / 약 " + totalMinutes + "개");
                });
                jobCancels.check(jobId);
                jobService.update(jobId, base + (int) (span * 0.85), "입력 검사", info.label() + " 검사 중");
                CandleSeries s = candleRepository.load(marketId, d.getRangeFrom(), d.getRangeTo());
                RawQuality.MarketQuality q = RawQuality.check(marketId, info.assetCode(), info.symbol(), info.quoteCurrency(),
                        s, d.getRangeFrom(), d.getRangeTo(), Instant.now());
                qualities.add(q);
                rows += q.rows();
            }
            d.setQuality(Json.write(qualities));
            d.setRowCount(rows);
            d.setStatus(BuildStatus.READY);
            d.setFinishedAt(Instant.now());
            d.setMessage(null);
            repository.save(d);
            jobService.succeed(jobId);
        } catch (RuntimeException e) {
            log.warn("원본 데이터 {} 실패: {}", id, e.getMessage(), e);
            String msg = e instanceof JobCancels.CancelledException ? "사용자가 중지했습니다. 지금까지 받은 1분봉은 저장된 채로 남습니다." : e.getMessage();
            d.setStatus(BuildStatus.FAILED);
            d.setMessage(msg);
            d.setFinishedAt(Instant.now());
            repository.save(d);
            jobService.fail(jobId, msg);
        } finally {
            jobCancels.clear(jobId);
        }
    }
}
