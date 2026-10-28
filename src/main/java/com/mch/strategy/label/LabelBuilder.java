package com.mch.strategy.label;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.collect.CandleSeries;
import com.mch.strategy.common.AsyncConfigNames;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.DataFiles;
import com.mch.strategy.common.Json;
import com.mch.strategy.feature.FeatureSet;
import com.mch.strategy.feature.FeatureSetRepository;
import com.mch.strategy.instrument.MarketCatalog;
import com.mch.strategy.job.JobCancels;
import com.mch.strategy.job.JobService;
import com.mch.strategy.raw.RawDataset;
import com.mch.strategy.raw.RawDatasetRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 백그라운드: 거래쌍마다 정답을 계산해 파일로 저장하고 요약을 남긴다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabelBuilder {

    private final LabelSetRepository repository;
    private final FeatureSetRepository featureRepository;
    private final RawDatasetRepository rawRepository;
    private final CandleRepository candleRepository;
    private final MarketCatalog marketCatalog;
    private final DataFiles dataFiles;
    private final JobService jobService;
    private final JobCancels jobCancels;

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void run(long id, long jobId) {
        LabelSet l = repository.findById(id).orElseThrow();
        Path dir = dataFiles.labelDir(id);
        try {
            jobService.start(jobId);
            l.setStatus(BuildStatus.BUILDING);
            l = repository.save(l);
            FeatureSet f = featureRepository.findById(l.getFeatureSetId()).orElseThrow();
            RawDataset raw = rawRepository.findById(f.getRawDatasetId()).orElseThrow();
            LabelConfig c = Json.read(l.getConfig(), LabelConfig.class);
            List<Long> markets = raw.marketIdList();
            List<LabelSummary.MarketSummary> sums = new ArrayList<>();
            long rows = 0;
            DataFiles.deleteTree(dir);
            for (int k = 0; k < markets.size(); k++) {
                long marketId = markets.get(k);
                String label = marketCatalog.get(marketId).label();
                jobCancels.check(jobId);
                jobService.update(jobId, k * 100 / markets.size(), "정답 계산", label);
                CandleSeries s = candleRepository.load(marketId, raw.getRangeFrom(), raw.getRangeTo());
                LabelCalculator.Result r = LabelCalculator.compute(s.time(), s.open(), s.high(), s.low(), s.close(), c);
                jobService.update(jobId, k * 100 / markets.size() + 50 / markets.size(), "저장", label);
                DataFiles.writeBytes(dir.resolve(marketId + ".status"), r.status);
                DataFiles.writeLongs(dir.resolve(marketId + ".end"), r.endTime);
                DataFiles.writeDoubles(dir.resolve(marketId + ".entry"), r.entry);
                DataFiles.writeInts(dir.resolve(marketId + ".bars"), r.bars);
                DataFiles.writeDoubles(dir.resolve(marketId + ".ret"), r.timeoutReturn);
                sums.add(LabelSummary.summarize(marketId, label, s.time(), r, raw.getRangeFrom().toEpochMilli(),
                        l.getExploreTo().toEpochMilli()));
                rows += r.size();
            }
            l.setSummary(Json.write(new LabelSummary(sums, LabelSummary.warnings(sums))));
            l.setRowCount(rows);
            l.setStatus(BuildStatus.READY);
            l.setFinishedAt(Instant.now());
            l.setMessage(null);
            repository.save(l);
            jobService.succeed(jobId);
        } catch (RuntimeException e) {
            log.warn("정답 {} 실패: {}", id, e.getMessage(), e);
            String msg = e instanceof JobCancels.CancelledException ? "사용자가 중지했습니다." : e.getMessage();
            DataFiles.deleteTree(dir);
            l.setStatus(BuildStatus.FAILED);
            l.setMessage(msg);
            l.setFinishedAt(Instant.now());
            repository.save(l);
            jobService.fail(jobId, msg);
        } finally {
            jobCancels.clear(jobId);
        }
    }
}
