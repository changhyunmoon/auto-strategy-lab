package com.mch.strategy.dataset;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mch.strategy.collect.CollectService;
import com.mch.strategy.collect.RawCollectService;
import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.feature.FeatureRequirement;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;

import lombok.RequiredArgsConstructor;

/** 데이터세트 만들기 요청 검증 · 저장 · 삭제 */
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DatasetQualityRepository qualityRepository;
    private final DatasetRowRepository rowRepository;
    private final AssetRepository assetRepository;
    private final MarketRepository marketRepository;
    private final FeatureRegistry featureRegistry;
    private final JobRepository jobRepository;
    private final CollectService collectService;
    private final DatasetBuilder builder;
    private final TransactionTemplate tx;

    public Map<String, Long> create(CreateDatasetRequest req) {
        List<Long> assetIds = req.assetIds().stream().distinct().sorted().toList();
        List<String> sources = normalizeSources(req.sources());
        List<String> intervals = Intervals.SUPPORTED.stream().filter(req.intervals()::contains).toList();
        if (intervals.isEmpty()) {
            throw ApiException.badRequest("봉 간격을 하나 이상 고르세요.");
        }
        RawCollectService.validateDates(req.from(), req.to());
        validateAssetsAndMarkets(assetIds, sources);
        List<FeatureDefinition> features = resolveFeatures(req.features(), assetIds.size(), sources);

        Instant from = req.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = req.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant warmupFrom = from.minusSeconds(warmupMinutes(features, intervals) * 60);
        checkRawData(assetIds, sources, warmupFrom, to);

        List<String> columnNames = new ArrayList<>(DatasetColumns.BAR_COLUMNS);
        features.forEach(f -> columnNames.addAll(f.outputColumns()));

        long[] ids = tx.execute(status -> {
            Dataset ds = datasetRepository.save(Dataset.create(req.name().trim(), from, to, warmupFrom,
                    intervals, assetIds, sources, columnNames.size()));
            List<DatasetColumn> cols = new ArrayList<>();
            int index = 0;
            for (String bar : DatasetColumns.BAR_COLUMNS) {
                cols.add(new DatasetColumn(ds.getId(), DatasetColumn.BAR, bar, index++));
            }
            for (FeatureDefinition f : features) {
                for (String col : f.outputColumns()) {
                    cols.add(new DatasetColumn(ds.getId(), f.code(), col, index++));
                }
            }
            columnRepository.saveAll(cols);
            Job job = jobRepository.save(Job.datasetBuild(ds.getId()));
            return new long[]{ds.getId(), job.getId()};
        });

        builder.build(ids[0], ids[1]); // 커밋 후 백그라운드 실행
        return Map.of("datasetId", ids[0], "jobId", ids[1]);
    }

    public void delete(long datasetId) {
        Dataset ds = datasetRepository.findById(datasetId).orElseThrow(() -> ApiException.notFound("데이터세트가 없습니다."));
        if (ds.isBuilding()) {
            throw ApiException.badRequest("만드는 중인 데이터세트는 끝난 뒤에 지울 수 있습니다.");
        }
        rowRepository.deleteByDataset(datasetId);
        tx.executeWithoutResult(status -> {
            columnRepository.deleteByDatasetId(datasetId);
            qualityRepository.deleteByDatasetId(datasetId);
            datasetRepository.deleteById(datasetId);
        });
    }

    private List<String> normalizeSources(List<String> sources) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : sources) {
            try {
                out.add(Exchange.valueOf(s).name());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("알 수 없는 데이터 출처: " + s);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 데이터세트는 받아 둔 원본(1분봉)으로만 만든다. 워밍업을 포함한 기간 중 받지 않은 구간이 있으면 알려준다.
     * 거래소 쪽 빈 구간(거래 없음 · 점검)은 받은 구간 안에 있으므로 문제 삼지 않고 품질 보고서에 나온다.
     */
    private void checkRawData(List<Long> assetIds, List<String> sources, Instant from, Instant to) {
        Map<Long, String> codes = new HashMap<>();
        assetRepository.findAllById(assetIds).forEach(a -> codes.put(a.getId(), a.getCode()));
        List<String> problems = new ArrayList<>();
        for (Long assetId : assetIds) {
            for (String s : sources) {
                Exchange ex = Exchange.valueOf(s);
                Market m = marketRepository.findFirstByAssetIdAndExchangeAndEnabledTrue(assetId, ex).orElseThrow();
                List<CollectService.Range> missing = collectService.missingRanges(m.getId(), from, to);
                if (!missing.isEmpty()) {
                    problems.add(codes.get(assetId) + " " + ex.getLabel() + " (" + rangeText(missing.get(0))
                            + (missing.size() > 1 ? " 외 " + (missing.size() - 1) + "곳" : "") + ")");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("받아 둔 원본이 모자랍니다: " + String.join(", ", problems)
                    + ". 「원본 데이터 받기」에서 " + rangeText(new CollectService.Range(from, to))
                    + " (지표 계산용 앞쪽 기간 포함)을 먼저 받아 주세요.");
        }
    }

    /** UTC 날짜로 "2026-10-01 ~ 2026-10-07" (끝은 그날 하루를 포함하는 날짜) */
    private static String rangeText(CollectService.Range r) {
        LocalDate from = LocalDate.ofInstant(r.from(), ZoneOffset.UTC);
        LocalDate to = LocalDate.ofInstant(r.to().minusMillis(1), ZoneOffset.UTC);
        return from + " ~ " + to;
    }

    private void validateAssetsAndMarkets(List<Long> assetIds, List<String> sources) {
        List<Asset> assets = assetRepository.findAllById(assetIds);
        if (assets.size() != assetIds.size() || assets.stream().anyMatch(a -> !a.isEnabled())) {
            throw ApiException.badRequest("없거나 사용하지 않는 종목이 포함되어 있습니다.");
        }
        for (Asset a : assets) {
            for (String s : sources) {
                Exchange ex = Exchange.valueOf(s);
                if (marketRepository.findFirstByAssetIdAndExchangeAndEnabledTrue(a.getId(), ex).isEmpty()) {
                    throw ApiException.badRequest(a.getCode() + "의 " + ex.getLabel() + " 마켓이 없거나 꺼져 있습니다.");
                }
            }
        }
    }

    private List<FeatureDefinition> resolveFeatures(List<String> codes, int assetCount, List<String> sources) {
        Set<String> wanted = codes == null ? Set.of() : Set.copyOf(codes);
        for (String code : wanted) {
            if (featureRegistry.find(code).isEmpty()) {
                throw ApiException.badRequest("알 수 없는 지표: " + code);
            }
        }
        List<FeatureDefinition> features = featureRegistry.all().stream().filter(f -> wanted.contains(f.code())).toList();
        boolean binance = sources.contains(Exchange.BINANCE.name());
        boolean both = binance && sources.contains(Exchange.UPBIT.name());
        for (FeatureDefinition f : features) {
            for (FeatureRequirement r : f.requirements()) {
                boolean ok = switch (r) {
                    case BINANCE_CANDLES -> binance;
                    case BOTH_EXCHANGES -> both;
                    case BOTH_ASSETS -> assetCount == 2;
                };
                if (!ok) {
                    throw ApiException.badRequest(f.name() + ": " + r.getMessage());
                }
            }
        }
        return features;
    }

    /** 가장 긴 워밍업(최소 1시간)을 시간 단위로 올림 */
    public static long warmupMinutes(List<FeatureDefinition> features, List<String> intervals) {
        long max = 60;
        for (FeatureDefinition f : features) {
            for (String interval : intervals) {
                max = Math.max(max, f.warmupMinutes(Intervals.minutes(interval)));
            }
        }
        return (max + 59) / 60 * 60;
    }
}
