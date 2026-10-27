package com.mch.strategy.collect;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mch.strategy.common.AsyncConfigNames;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.JobCancels;
import com.mch.strategy.job.JobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 원본 데이터 받기 (백그라운드). 마켓마다 아직 받지 않은 구간만 받아 candle_1m 에 저장한다.
 * 이미 받은 구간은 건너뛰고, 겹치는 봉은 INSERT IGNORE 로 중복 저장하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RawCollectRunner {

    private final MarketRepository marketRepository;
    private final CollectService collectService;
    private final JobService jobService;
    private final JobCancels cancels;

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void run(long jobId, List<Long> marketIds, Instant from, Instant to) {
        try {
            jobService.start(jobId);
            long total = 0;
            for (int i = 0; i < marketIds.size(); i++) {
                Market m = marketRepository.findById(marketIds.get(i)).orElseThrow();
                total += collectMarket(jobId, m, i, marketIds.size(), from, to);
            }
            log.info("원본 받기 완료: 마켓 {}개 · 새로 받은 봉 {}개", marketIds.size(), total);
            jobService.succeed(jobId);
        } catch (JobCancels.CancelledException e) {
            log.info("원본 받기 작업 {} 을 사용자가 중지했습니다.", jobId);
            jobService.fail(jobId, "중지했습니다. 끝까지 받은 마켓 · 구간은 저장되어 있고, 다시 받으면 남은 구간만 받습니다.");
        } catch (Exception e) {
            log.error("원본 받기 작업 {} 실패", jobId, e);
            jobService.fail(jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            cancels.clear(jobId);
        }
    }

    private long collectMarket(long jobId, Market m, int index, int count, Instant from, Instant to) {
        String label = m.getExchange().getLabel() + " " + m.getSymbol() + " (" + (index + 1) + "/" + count + ")";
        List<CollectService.Range> missing = collectService.missingRanges(m.getId(), from, to);
        if (missing.isEmpty()) {
            jobService.update(jobId, pct(index, count, 1), label, "이미 모두 받은 구간이라 건너뜀");
            return 0;
        }
        long expected = Math.max(1, missing.stream().mapToLong(r -> (r.to().toEpochMilli() - r.from().toEpochMilli()) / 60_000L).sum());
        jobService.update(jobId, pct(index, count, 0), label, "받는 중 · 빈 구간 " + missing.size() + "개");
        long done = 0;
        long[] lastSaved = {0};
        for (CollectService.Range r : missing) {
            long base = done;
            cancels.check(jobId);
            int got = collectService.collect(m, r.from(), r.to(), rows -> {
                cancels.check(jobId);
                long now = System.currentTimeMillis();
                if (now - lastSaved[0] >= 1000) { // DB 에 너무 자주 쓰지 않도록 1초에 한 번만
                    lastSaved[0] = now;
                    jobService.update(jobId, pct(index, count, Math.min(1.0, (base + rows) / (double) expected)), label,
                            String.format("%,d", base + rows) + "개 받음");
                }
            });
            done += Math.max(0, got);
        }
        jobService.update(jobId, pct(index, count, 1), label, String.format("%,d", done) + "개 받음");
        return done;
    }

    private static int pct(int index, int total, double fraction) {
        return (int) Math.round(100.0 * (index + fraction) / total);
    }
}
