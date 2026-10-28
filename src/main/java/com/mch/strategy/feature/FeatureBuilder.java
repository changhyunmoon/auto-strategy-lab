package com.mch.strategy.feature;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.collect.CandleSeries;
import com.mch.strategy.common.AsyncConfigNames;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.DataFiles;
import com.mch.strategy.common.Json;
import com.mch.strategy.indicator.ColumnSpec;
import com.mch.strategy.indicator.Ctx;
import com.mch.strategy.indicator.IndicatorConfig;
import com.mch.strategy.indicator.IndicatorPlan;
import com.mch.strategy.indicator.IndicatorPlanner;
import com.mch.strategy.instrument.MarketCatalog;
import com.mch.strategy.job.JobCancels;
import com.mch.strategy.job.JobService;
import com.mch.strategy.raw.RawDataset;
import com.mch.strategy.raw.RawDatasetRepository;
import com.mch.strategy.raw.RawQuality;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 백그라운드: 거래쌍마다 원본 1분봉을 읽어 지표 열을 계산하고 파일로 저장한다.
 * 빈 1분에서 끊긴 구간마다 따로 계산한다 (끊긴 시계열을 이어진 것처럼 계산하지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureBuilder {

    private final FeatureSetRepository repository;
    private final RawDatasetRepository rawRepository;
    private final CandleRepository candleRepository;
    private final MarketCatalog marketCatalog;
    private final DataFiles dataFiles;
    private final JobService jobService;
    private final JobCancels jobCancels;

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void run(long id, long jobId) {
        FeatureSet f = repository.findById(id).orElseThrow();
        Path dir = dataFiles.featureDir(id);
        try {
            jobService.start(jobId);
            f.setStatus(BuildStatus.BUILDING);
            f = repository.save(f);
            RawDataset raw = rawRepository.findById(f.getRawDatasetId()).orElseThrow();
            IndicatorPlan plan = IndicatorPlanner.plan(Json.read(f.getConfig(), IndicatorConfig.class));
            List<ColumnSpec> cols = plan.columns();
            int nc = cols.size();
            List<Long> markets = raw.marketIdList();
            long[][] nulls = new long[nc][markets.size()];
            Instant[][] first = new Instant[nc][markets.size()];
            long totalRows = 0;
            DataFiles.deleteTree(dir);
            Files.createDirectories(dir);

            for (int k = 0; k < markets.size(); k++) {
                long marketId = markets.get(k);
                String label = marketCatalog.get(marketId).label();
                int base = k * 100 / markets.size();
                int span = 100 / markets.size();
                jobService.update(jobId, base, "원본 읽기", label);
                CandleSeries s = candleRepository.load(marketId, raw.getRangeFrom(), raw.getRangeTo());
                int n = s.size();
                totalRows += n;
                long[] time = s.time();
                int[] rowNulls = new int[n];
                List<int[]> segments = RawQuality.segments(time);
                try (FileChannel ch = FileChannel.open(dir.resolve(marketId + ".cols"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    long done = 0;
                    for (int[] seg : segments) {
                        int from = seg[0];
                        int len = seg[1] - seg[0];
                        Ctx ctx = new Ctx(slice(s.open(), from, len), slice(s.high(), from, len), slice(s.low(), from, len),
                                slice(s.close(), from, len), slice(s.volume(), from, len));
                        for (int c = 0; c < nc; c++) {
                            if (c % 25 == 0) {
                                jobCancels.check(jobId);
                                int pct = base + (int) (span * ((done + (double) len * c / nc) / Math.max(1, n)));
                                jobService.update(jobId, pct, "지표 계산", label + " · 열 " + (c + 1) + " / " + nc
                                        + (segments.size() > 1 ? " · 구간 " + (segments.indexOf(seg) + 1) + " / " + segments.size() : ""));
                            }
                            double[] v = cols.get(c).calc().apply(ctx);
                            for (int i = 0; i < len; i++) {
                                if (Double.isNaN(v[i]) || Double.isInfinite(v[i])) {
                                    nulls[c][k]++;
                                    rowNulls[from + i]++;
                                } else if (first[c][k] == null || time[from + i] < first[c][k].toEpochMilli()) {
                                    first[c][k] = Instant.ofEpochMilli(time[from + i]);
                                }
                            }
                            DataFiles.writeFloatsAt(ch, n, c, from, v, 0, len);
                        }
                        done += len;
                    }
                }
                DataFiles.writeLongs(dir.resolve(marketId + ".time"), time);
                DataFiles.writeInts(dir.resolve(marketId + ".nulls"), rowNulls);
            }

            List<ColumnView> views = new ArrayList<>();
            for (int c = 0; c < nc; c++) {
                List<ColumnView.MarketStat> st = new ArrayList<>();
                for (int k = 0; k < markets.size(); k++) {
                    st.add(new ColumnView.MarketStat(markets.get(k), nulls[c][k], first[c][k]));
                }
                views.add(ColumnView.of(c, cols.get(c)).withStats(st));
            }
            f.setColumnsJson(Json.write(views));
            f.setRowCount(totalRows);
            f.setStatus(BuildStatus.READY);
            f.setFinishedAt(Instant.now());
            f.setMessage(null);
            repository.save(f);
            jobService.succeed(jobId);
        } catch (RuntimeException | IOException e) {
            log.warn("지표 데이터셋 {} 실패: {}", id, e.getMessage(), e);
            String msg = e instanceof JobCancels.CancelledException ? "사용자가 중지했습니다." : e.getMessage();
            DataFiles.deleteTree(dir);
            f.setStatus(BuildStatus.FAILED);
            f.setMessage(msg);
            f.setFinishedAt(Instant.now());
            repository.save(f);
            jobService.fail(jobId, msg);
        } finally {
            jobCancels.clear(jobId);
        }
    }

    private static double[] slice(double[] a, int from, int len) {
        return Arrays.copyOfRange(a, from, from + len);
    }
}
