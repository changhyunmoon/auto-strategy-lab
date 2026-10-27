package com.mch.strategy.dataset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mch.strategy.candle.CandleAggregator;
import com.mch.strategy.candle.QualityChecker;
import com.mch.strategy.candle.SeriesAlign;
import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.collect.CandleSeries;
import com.mch.strategy.common.AsyncConfigNames;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.common.JsonArrays;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureInput;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.JobCancels;
import com.mch.strategy.job.JobService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 데이터세트 만들기 (백그라운드).
 * 받아 둔 원본(1분봉) 불러오기(0~5%) → 자산·간격별 재집계와 지표 계산·저장(5~95%) → 품질 보고서(95~100%).
 * 원본은 받지 않는다. 원본은 「원본 데이터 받기」에서 미리 받아 둔다 (만들기 요청 때 있는지 확인한다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasetBuilder {

    private static final int INSERT_CHUNK = 2000;

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final MarketRepository marketRepository;
    private final AssetRepository assetRepository;
    private final CandleRepository candleRepository;
    private final FeatureRegistry featureRegistry;
    private final DatasetRowRepository rowRepository;
    private final DatasetStateService state;
    private final JobService jobService;
    private final JobCancels cancels;

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void build(long datasetId, long jobId) {
        Progress progress = new Progress(jobId);
        try {
            jobService.start(jobId);
            run(datasetId, progress);
            jobService.succeed(jobId);
        } catch (JobCancels.CancelledException e) {
            log.info("데이터세트 {} 만들기를 사용자가 중지했습니다.", datasetId);
            String message = "중지했습니다. 원본 1분봉은 그대로 남아 있으니 같은 설정으로 다시 만들면 됩니다.";
            state.fail(datasetId, message);
            jobService.fail(jobId, message);
        } catch (Exception e) {
            log.error("데이터세트 {} 만들기 실패", datasetId, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            state.fail(datasetId, message);
            jobService.fail(jobId, message);
        } finally {
            cancels.clear(jobId);
        }
    }

    private void run(long datasetId, Progress progress) {
        Dataset ds = datasetRepository.findById(datasetId).orElseThrow();
        List<DatasetColumn> columns = columnRepository.findByDatasetIdOrderByColumnIndexAsc(datasetId);
        List<FeatureDefinition> features = selectedFeatures(columns);
        Instant from = ds.getWarmupFrom();
        Instant to = ds.getRangeTo();
        boolean upbit = ds.getSources().contains(Exchange.UPBIT.name());
        boolean binance = ds.getSources().contains(Exchange.BINANCE.name());

        // 자산별 실행 시장(업비트, 없으면 바이낸스)과 참고 시장(바이낸스)
        Map<Long, Market> primary = new LinkedHashMap<>();
        Map<Long, Market> reference = new LinkedHashMap<>();
        for (Long assetId : ds.getAssetIds()) {
            primary.put(assetId, market(assetId, upbit ? Exchange.UPBIT : Exchange.BINANCE));
            if (binance && upbit) {
                reference.put(assetId, market(assetId, Exchange.BINANCE));
            }
        }

        // 1. 받아 둔 1분봉 불러오기 + 재집계 + 지표 계산
        state.changeStatus(datasetId, DatasetStatus.AGGREGATING);
        progress.set(0, "재집계", "받아 둔 1분봉 불러오는 중", true);
        Map<Long, CandleSeries> primary1m = new LinkedHashMap<>();
        Map<Long, CandleSeries> reference1m = new LinkedHashMap<>();
        primary.forEach((assetId, m) -> primary1m.put(assetId, loadCandles(m, from, to, progress)));
        reference.forEach((assetId, m) -> reference1m.put(assetId, loadCandles(m, from, to, progress)));

        state.changeStatus(datasetId, DatasetStatus.COMPUTING);
        List<Long> assetIds = ds.getAssetIds();
        Map<Long, String> assetCodes = assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, Asset::getCode));
        int totalSteps = assetIds.size() * ds.getIntervals().size();
        int step = 0;
        long rowCount = 0;
        int fullPrecision = DatasetColumns.BAR_COLUMNS.size();
        for (Long assetId : assetIds) {
            Long otherId = assetIds.size() == 2 ? assetIds.get(assetIds.get(0).equals(assetId) ? 1 : 0) : null;
            for (String interval : ds.getIntervals()) {
                progress.set(pct(5, 95, step, totalSteps, 0), "지표 계산",
                        assetCodes.getOrDefault(assetId, "자산 " + assetId) + " · " + Intervals.label(interval), true);
                int minutes = Intervals.minutes(interval);
                CandleSeries prim = CandleAggregator.aggregate(primary1m.get(assetId), minutes);
                CandleSeries ref = !binance ? null
                        : upbit ? CandleAggregator.aggregate(reference1m.get(assetId), minutes) : prim;
                CandleSeries other = otherId == null ? null : CandleAggregator.aggregate(primary1m.get(otherId), minutes);

                FeatureInput input = toInput(prim, ref, other, minutes);
                Map<String, double[]> values = new LinkedHashMap<>();
                for (FeatureDefinition f : features) {
                    values.putAll(f.compute(input));
                }
                rowCount += writeRows(datasetId, assetId, interval, prim, columns, values, ds.getRangeFrom(), fullPrecision, progress);
                step++;
            }
        }

        // 2. 품질 보고서 (워밍업 제외한 데이터세트 기간 기준)
        state.changeStatus(datasetId, DatasetStatus.CHECKING);
        progress.set(95, "품질 검사", "누락 구간 확인 중", true);
        List<DatasetQuality> qualities = new ArrayList<>();
        primary.forEach((assetId, m) -> qualities.add(quality(datasetId, m, primary1m.get(assetId), ds)));
        reference.forEach((assetId, m) -> qualities.add(quality(datasetId, m, reference1m.get(assetId), ds)));
        state.saveQuality(datasetId, qualities);

        state.ready(datasetId, rowCount);
    }

    private List<FeatureDefinition> selectedFeatures(List<DatasetColumn> columns) {
        List<String> codes = columns.stream().filter(c -> !c.isBar()).map(DatasetColumn::getFeatureCode).distinct().toList();
        Map<String, FeatureDefinition> byCode = featureRegistry.all().stream()
                .collect(Collectors.toMap(FeatureDefinition::code, Function.identity()));
        return codes.stream().map(code -> {
            FeatureDefinition f = byCode.get(code);
            if (f == null) {
                throw new IllegalStateException("등록되지 않은 지표: " + code);
            }
            return f;
        }).toList();
    }

    private CandleSeries loadCandles(Market m, Instant from, Instant to, Progress progress) {
        String label = m.getExchange().getLabel() + " " + m.getSymbol();
        return candleRepository.load(m.getId(), from, to,
                n -> progress.set(0, "재집계", label + " 1분봉 " + String.format("%,d", n) + "개 불러옴", false));
    }

    private Market market(long assetId, Exchange exchange) {
        return marketRepository.findFirstByAssetIdAndExchangeAndEnabledTrue(assetId, exchange)
                .orElseThrow(() -> new IllegalStateException(exchange.getLabel() + " 마켓이 없거나 꺼져 있습니다 (자산 " + assetId + ")"));
    }

    static FeatureInput toInput(CandleSeries prim, CandleSeries ref, CandleSeries other, int minutes) {
        long[] t = prim.time();
        int n = t.length;
        return new FeatureInput(t, prim.open(), prim.high(), prim.low(), prim.close(), prim.volume(), prim.quoteVolume(),
                ref == null ? SeriesAlign.nanArray(n) : SeriesAlign.align(t, ref.time(), ref.close()),
                ref == null ? SeriesAlign.nanArray(n) : SeriesAlign.align(t, ref.time(), ref.volume()),
                ref == null ? SeriesAlign.nanArray(n) : SeriesAlign.align(t, ref.time(), ref.takerBuyVolume()),
                ref == null ? SeriesAlign.nanArray(n) : SeriesAlign.align(t, ref.time(), ref.tradeCount()),
                other == null ? SeriesAlign.nanArray(n) : SeriesAlign.align(t, other.time(), other.close()),
                minutes);
    }

    /** 열 순서대로 한 행씩 JSON 배열을 만들어 저장한다. 저장한 행 중 워밍업이 아닌 행 수를 돌려준다. */
    private long writeRows(long datasetId, long assetId, String interval, CandleSeries bars,
                           List<DatasetColumn> columns, Map<String, double[]> featureValues,
                           Instant rangeFrom, int fullPrecision, Progress progress) {
        double[][] byColumn = new double[columns.size()][];
        for (DatasetColumn c : columns) {
            double[] values = c.isBar() ? DatasetColumns.barValues(bars, c.getColumnName()) : featureValues.get(c.getColumnName());
            if (values == null) {
                throw new IllegalStateException("열 값을 만들지 못했습니다: " + c.getColumnName());
            }
            byColumn[c.getColumnIndex()] = values;
        }
        long rangeStart = rangeFrom.toEpochMilli();
        long kept = 0;
        List<DatasetRowRepository.RowInsert> chunk = new ArrayList<>(INSERT_CHUNK);
        double[] row = new double[byColumn.length];
        for (int i = 0; i < bars.size(); i++) {
            for (int c = 0; c < byColumn.length; c++) {
                row[c] = byColumn[c][i];
            }
            boolean warmup = bars.time()[i] < rangeStart;
            if (!warmup) {
                kept++;
            }
            chunk.add(new DatasetRowRepository.RowInsert(bars.time()[i], warmup, JsonArrays.write(row, fullPrecision)));
            if (chunk.size() == INSERT_CHUNK) {
                progress.checkCancel();
                rowRepository.insert(datasetId, assetId, interval, chunk);
                chunk.clear();
            }
        }
        rowRepository.insert(datasetId, assetId, interval, chunk);
        return kept;
    }

    private DatasetQuality quality(long datasetId, Market m, CandleSeries series, Dataset ds) {
        QualityChecker.Result r = QualityChecker.check(series.time(), ds.getRangeFrom(), ds.getRangeTo());
        List<Map<String, Object>> gaps = r.gaps().stream()
                .map(g -> Map.<String, Object>of("from", g.from().toString(), "to", g.to().toString(), "minutes", g.minutes()))
                .toList();
        String notes = r.gapCount() == 0 ? "정상"
                : "빈 구간 " + r.gapCount() + "개 · " + String.format("%,d", r.missingMinutes()) + "분";
        return new DatasetQuality(datasetId, m.getId(), r.expectedMinutes(), r.actualMinutes(), gaps, notes);
    }

    private static int pct(int start, int end, int index, int total, double fraction) {
        if (total == 0) {
            return end;
        }
        return (int) Math.round(start + (end - start) * (index + fraction) / total);
    }

    /** 진행률 기록. 너무 자주 DB 에 쓰지 않도록 1초에 한 번만 저장한다 (force 면 바로). */
    private final class Progress {
        private final long jobId;
        private long lastSaved = 0;

        Progress(long jobId) {
            this.jobId = jobId;
        }

        void set(int percent, String step, String message, boolean force) {
            checkCancel();
            long now = System.currentTimeMillis();
            if (!force && now - lastSaved < 1000) {
                return;
            }
            lastSaved = now;
            jobService.update(jobId, percent, step, message);
        }

        /** 「중지」를 눌렀으면 여기서 멈춘다 */
        void checkCancel() {
            cancels.check(jobId);
        }
    }
}
