package com.mch.strategy.raw;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.BuildStatus;
import com.mch.strategy.common.Json;
import com.mch.strategy.instrument.Exchange;
import com.mch.strategy.instrument.MarketCatalog;
import com.mch.strategy.instrument.MarketCatalog.MarketInfo;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;

import lombok.RequiredArgsConstructor;

/** 원본 데이터셋 만들기 · 조회 · 삭제 */
@Service
@RequiredArgsConstructor
public class RawService {

    private final RawDatasetRepository repository;
    private final MarketCatalog marketCatalog;
    private final JobRepository jobRepository;
    private final RawBuilder builder;

    /**
     * @param from 시작일(UTC, 포함)
     * @param to   종료일(UTC, 하루 전체 포함). 어제까지만 고를 수 있다 (완료된 봉만 받기 위해서)
     */
    public record CreateRequest(String name, Exchange exchange, List<Long> assetIds, LocalDate from, LocalDate to) {
    }

    public record Created(long id, long jobId) {
    }

    public Created create(CreateRequest r) {
        if (r.name() == null || r.name().isBlank()) throw ApiException.badRequest("원본 데이터 이름을 입력하세요.");
        if (r.exchange() == null) throw ApiException.badRequest("거래소를 고르세요.");
        if (r.assetIds() == null || r.assetIds().isEmpty()) throw ApiException.badRequest("종목을 하나 이상 고르세요.");
        if (r.from() == null || r.to() == null || r.from().isAfter(r.to())) throw ApiException.badRequest("기간을 확인하세요.");
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        if (r.to().isAfter(yesterday)) throw ApiException.badRequest("종료일은 어제(UTC " + yesterday + ")까지 고를 수 있습니다. 끝나지 않은 봉을 받지 않기 위해서입니다.");
        if (r.from().isBefore(LocalDate.of(2017, 9, 1))) throw ApiException.badRequest("2017-09-01 이후만 받을 수 있습니다.");
        if (r.from().plusDays(800).isBefore(r.to())) throw ApiException.badRequest("한 번에 800일까지 받을 수 있습니다.");

        List<Long> marketIds = new ArrayList<>();
        for (long assetId : r.assetIds()) {
            MarketInfo m = marketCatalog.all().values().stream()
                    .filter(x -> x.assetId() == assetId && x.exchange() == r.exchange() && x.enabled())
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("이 종목은 " + r.exchange().getLabel() + " 에 사용 중인 거래쌍이 없습니다 (종목 관리 확인)."));
            marketIds.add(m.id());
        }

        RawDataset d = new RawDataset();
        d.setName(r.name().trim());
        d.setStatus(BuildStatus.PENDING);
        d.setExchange(r.exchange());
        d.setMarketIdList(marketIds);
        d.setRangeFrom(r.from().atStartOfDay(ZoneOffset.UTC).toInstant());
        d.setRangeTo(r.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        d.setCreatedAt(Instant.now());
        d = repository.save(d);
        Job job = jobRepository.save(Job.forTarget(Job.TYPE_RAW_BUILD, d.getId()));
        builder.run(d.getId(), job.getId());
        return new Created(d.getId(), job.getId());
    }

    public RawDataset get(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("원본 데이터가 없습니다: " + id));
    }

    public List<RawDataset> list() {
        return repository.findAllByOrderByIdDesc();
    }

    public List<RawDataset> readyList() {
        return repository.findByStatusOrderByIdDesc(BuildStatus.READY);
    }

    public static List<RawQuality.MarketQuality> quality(RawDataset d) {
        if (d.getQuality() == null) return List.of();
        return Json.read(d.getQuality(), new TypeReference<List<RawQuality.MarketQuality>>() { });
    }

    public void delete(long id) {
        repository.delete(get(id));
    }
}
