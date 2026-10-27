package com.mch.strategy.collect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.dataset.DatasetService;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.job.JobStatus;

import lombok.RequiredArgsConstructor;

/** 원본 데이터 받기: 요청 검증 · 작업 만들기 · 받아 둔 구간과 이력 보기 */
@Service
@RequiredArgsConstructor
public class RawCollectService {

    /** 이보다 이른 날짜는 받지 않는다 (업비트 원화 시장 초기) */
    public static final LocalDate MIN_DATE = LocalDate.of(2017, 10, 1);

    private final AssetRepository assetRepository;
    private final MarketRepository marketRepository;
    private final CollectService collectService;
    private final CollectLogRepository collectLogRepository;
    private final CandleRepository candleRepository;
    private final FeatureRegistry featureRegistry;
    private final JobRepository jobRepository;
    private final RawCollectRunner runner;

    public Map<String, Object> create(RawCollectRequest req) {
        validateDates(req.from(), req.to());
        if (jobRepository.findFirstByJobTypeAndStatusOrderByIdDesc(Job.TYPE_RAW_COLLECT, JobStatus.RUNNING).isPresent()) {
            throw ApiException.badRequest("이미 받는 중인 작업이 있습니다. 끝난 뒤에 다시 받아 주세요.");
        }
        List<Market> markets = markets(req.assetIds(), req.exchanges());
        Instant from = req.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        if (req.includeWarmup()) {
            from = from.minusSeconds(maxWarmupDays() * 86_400L);
        }
        Instant to = req.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Job job = jobRepository.save(Job.rawCollect());
        runner.run(job.getId(), markets.stream().map(Market::getId).toList(), from, to); // 저장 후 백그라운드 실행
        return Map.of("jobId", job.getId(), "from", from.toString(), "to", to.toString());
    }

    /** 데이터세트를 만들 때 필요한 가장 긴 워밍업(모든 지표 · 모든 간격 기준)을 일 단위로 올림 */
    public long maxWarmupDays() {
        long minutes = DatasetService.warmupMinutes(featureRegistry.all(), Intervals.SUPPORTED);
        return (minutes + 1439) / 1440;
    }

    /** 진행 중인 받기 작업 번호 (화면을 다시 열었을 때 진행률을 이어서 보여준다) */
    public Long runningJobId() {
        return jobRepository.findFirstByJobTypeAndStatusOrderByIdDesc(Job.TYPE_RAW_COLLECT, JobStatus.RUNNING)
                .map(Job::getId).orElse(null);
    }

    /** 마켓별로 받아 둔 원본: 봉 수 · 처음/마지막 봉 · 빈틈없이 받은 구간들 (모두 UTC) */
    public List<Map<String, Object>> coverage() {
        Map<Long, CandleRepository.Coverage> byMarket = new LinkedHashMap<>();
        candleRepository.coverage().forEach(c -> byMarket.put(c.marketId(), c));
        Map<Long, Asset> assets = assetRepository.findAll().stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Market m : marketRepository.findAllByOrderByIdAsc()) {
            CandleRepository.Coverage c = byMarket.get(m.getId());
            Asset asset = assets.get(m.getAsset().getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("marketId", m.getId());
            row.put("assetId", m.getAsset().getId());
            row.put("assetCode", asset == null ? "" : asset.getCode());
            row.put("exchange", m.getExchange().name());
            row.put("exchangeLabel", m.getExchange().getLabel());
            row.put("symbol", m.getSymbol());
            row.put("enabled", m.isEnabled());
            row.put("first", c == null ? null : c.first().toString());
            row.put("last", c == null ? null : c.last().toString());
            row.put("count", c == null ? 0 : c.count());
            row.put("ranges", collectService.collectedRanges(m.getId()).stream()
                    .map(r -> Map.of("from", r.from().toString(), "to", r.to().toString())).toList());
            out.add(row);
        }
        return out;
    }

    /** 최근 받기 이력 50건 */
    public List<Map<String, Object>> logs() {
        Map<Long, Market> markets = marketRepository.findAll().stream().collect(Collectors.toMap(Market::getId, Function.identity()));
        Map<Long, Asset> assets = assetRepository.findAll().stream().collect(Collectors.toMap(Asset::getId, Function.identity()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollectLog log : collectLogRepository.findTop50ByOrderByIdDesc()) {
            Market m = markets.get(log.getMarketId());
            Asset a = m == null ? null : assets.get(m.getAsset().getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", log.getId());
            row.put("market", m == null ? "마켓 " + log.getMarketId() : m.getExchange().getLabel() + " " + m.getSymbol());
            row.put("assetCode", a == null ? "" : a.getCode());
            row.put("from", log.getRangeFrom().toString());
            row.put("to", log.getRangeTo().toString());
            row.put("rowCount", log.getRowCount());
            row.put("status", log.getStatus());
            row.put("error", log.getErrorMessage() == null ? "" : log.getErrorMessage());
            row.put("requestedAt", log.getRequestedAt().toString());
            out.add(row);
        }
        return out;
    }

    /** 고른 종목 × 거래소의 마켓. 하나라도 없거나 꺼져 있으면 알려준다 */
    private List<Market> markets(List<Long> assetIds, List<String> exchanges) {
        List<Asset> assets = assetRepository.findAllById(assetIds.stream().distinct().sorted().toList());
        if (assets.size() != assetIds.stream().distinct().count() || assets.stream().anyMatch(a -> !a.isEnabled())) {
            throw ApiException.badRequest("없거나 사용하지 않는 종목이 포함되어 있습니다.");
        }
        Set<Exchange> exs = new LinkedHashSet<>();
        for (String e : exchanges) {
            try {
                exs.add(Exchange.valueOf(e));
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("알 수 없는 거래소: " + e);
            }
        }
        List<Market> out = new ArrayList<>();
        for (Exchange ex : exs) {
            for (Asset a : assets) {
                out.add(marketRepository.findFirstByAssetIdAndExchangeAndEnabledTrue(a.getId(), ex)
                        .orElseThrow(() -> ApiException.badRequest(a.getCode() + "의 " + ex.getLabel() + " 마켓이 없거나 꺼져 있습니다.")));
            }
        }
        return out;
    }

    /** 시작일 ≤ 종료일, 종료일은 어제(UTC)까지, 시작일은 MIN_DATE 이후 */
    public static void validateDates(LocalDate from, LocalDate to) {
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
}
