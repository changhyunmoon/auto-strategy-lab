package com.mch.strategy.dataset;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.feature.FeatureRequirement;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;

import lombok.RequiredArgsConstructor;

/** 데이터세트 만들기 요청 검증 · 저장 · 삭제 */
@Service
@RequiredArgsConstructor
public class DatasetService {

    /** 이보다 이른 날짜는 받지 않는다 (업비트 원화 시장 초기) */
    private static final LocalDate MIN_DATE = LocalDate.of(2017, 10, 1);

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DatasetQualityRepository qualityRepository;
    private final DatasetRowRepository rowRepository;
    private final AssetRepository assetRepository;
    private final MarketRepository marketRepository;
    private final FeatureRegistry featureRegistry;
    private final JobRepository jobRepository;
    private final DatasetBuilder builder;
    private final TransactionTemplate tx;

    public Map<String, Long> create(CreateDatasetRequest req) {
        List<Long> assetIds = req.assetIds().stream().distinct().sorted().toList();
        List<String> sources = normalizeSources(req.sources());
        List<String> intervals = Intervals.SUPPORTED.stream().filter(req.intervals()::contains).toList();
        if (intervals.isEmpty()) {
            throw ApiException.badRequest("봉 간격을 하나 이상 고르세요.");
        }
        validateDates(req.from(), req.to());
        validateAssetsAndMarkets(assetIds, sources);
        List<FeatureDefinition> features = resolveFeatures(req.features(), assetIds.size(), sources);

        Instant from = req.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = req.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant warmupFrom = from.minusSeconds(warmupMinutes(features, intervals) * 60);

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

    private void validateDates(LocalDate from, LocalDate to) {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        if (from.isAfter(to)) {
            throw ApiException.badRequest("시작일이 종료일보다 늦습니다.");
        }
        if (to.isAfter(yesterday)) {
            throw ApiException.badRequest("종료일은 " + yesterday + " (어제, UTC)까지 고를 수 있습니다.");
        }
        if (from.isBefore(MIN_DATE)) {
            throw ApiException.badRequest("시작일은 " + MIN_DATE + " 이후로 고르세요.");
        }
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
    static long warmupMinutes(List<FeatureDefinition> features, List<String> intervals) {
        long max = 60;
        for (FeatureDefinition f : features) {
            for (String interval : intervals) {
                max = Math.max(max, f.warmupMinutes(Intervals.minutes(interval)));
            }
        }
        return (max + 59) / 60 * 60;
    }
}
