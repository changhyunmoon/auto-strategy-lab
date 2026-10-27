package com.mch.strategy.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.TimeUtils;
import com.mch.strategy.dataset.CreateDatasetRequest;
import com.mch.strategy.dataset.DatasetQueryService;
import com.mch.strategy.dataset.DatasetService;
import com.mch.strategy.dataset.DatasetViews;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.JobService;
import com.mch.strategy.viz.FeatureProfileService;
import com.mch.strategy.viz.VizService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 화면의 jQuery 가 호출하는 JSON API. 오류는 {"message": ...} 로 돌아간다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final DatasetService datasetService;
    private final DatasetQueryService datasetQueryService;
    private final JobService jobService;
    private final com.mch.strategy.job.JobCancels jobCancels;
    private final VizService vizService;
    private final FeatureProfileService featureProfileService;
    private final CandleRepository candleRepository;
    private final MarketRepository marketRepository;
    private final AssetRepository assetRepository;

    // ---------- 데이터 가져오기 ----------

    /** 마켓별로 DB 에 이미 있는 1분봉 기간 */
    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage() {
        Map<Long, CandleRepository.Coverage> byMarket = new LinkedHashMap<>();
        candleRepository.coverage().forEach(c -> byMarket.put(c.marketId(), c));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Market m : marketRepository.findAllByOrderByIdAsc()) {
            CandleRepository.Coverage c = byMarket.get(m.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("marketId", m.getId());
            row.put("exchange", m.getExchange().name());
            row.put("symbol", m.getSymbol());
            row.put("first", c == null ? null : TimeUtils.toKstText(c.first()).substring(0, 10));
            row.put("last", c == null ? null : TimeUtils.toKstText(c.last()).substring(0, 10));
            row.put("count", c == null ? 0 : c.count());
            out.add(row);
        }
        return out;
    }

    @PostMapping("/datasets")
    public Map<String, Long> createDataset(@Valid @RequestBody CreateDatasetRequest request) {
        return datasetService.create(request);
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable long id) {
        return jobService.view(id);
    }

    /** 진행 중인 데이터세트 만들기(수집 · 지표 계산)를 멈춘다. 몇 초 안에 다음 확인 지점에서 멈춘다. */
    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancelJob(@PathVariable long id) {
        Map<String, Object> job = jobService.view(id);
        if (!"RUNNING".equals(job.get("status"))) {
            throw ApiException.badRequest("이미 끝난 작업입니다.");
        }
        jobCancels.request(id);
        return Map.of("id", id, "cancelRequested", true);
    }

    // ---------- 데이터세트 ----------

    @GetMapping("/datasets")
    public List<DatasetViews.Summary> datasets() {
        return datasetQueryService.list();
    }

    @GetMapping("/datasets/{id}")
    public DatasetViews.Detail dataset(@PathVariable long id) {
        return datasetQueryService.detail(id);
    }

    @DeleteMapping("/datasets/{id}")
    public Map<String, Object> deleteDataset(@PathVariable long id) {
        datasetService.delete(id);
        return Map.of("deleted", id);
    }

    @GetMapping("/datasets/{id}/rows")
    public Map<String, Object> rows(@PathVariable long id,
                                    @RequestParam long assetId,
                                    @RequestParam String interval,
                                    @RequestParam(defaultValue = "false") boolean warmup,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return datasetQueryService.rows(id, assetId, interval, warmup, page, size);
    }

    // ---------- 시각화 ----------

    @GetMapping("/viz/{id}/series")
    public Map<String, Object> series(@PathVariable long id,
                                      @RequestParam long assetId,
                                      @RequestParam String interval,
                                      @RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to,
                                      @RequestParam(required = false) List<String> columns,
                                      @RequestParam(required = false) Integer maxPoints) {
        return vizService.series(id, assetId, interval,
                from == null ? null : Instant.ofEpochMilli(from),
                to == null ? null : Instant.ofEpochMilli(to),
                columns, maxPoints);
    }

    @GetMapping("/viz/{id}/correlation")
    public Map<String, Object> correlation(@PathVariable long id, @RequestParam long assetId,
                                           @RequestParam String interval,
                                           @RequestParam(required = false) List<String> columns) {
        return vizService.correlation(id, assetId, interval, columns);
    }

    /** 변수 탐색: 한 열의 분포 · N봉 뒤 수익률과의 관계 · 지속성 · 시간대 · 관련 변수 */
    @GetMapping("/viz/{id}/profile")
    public Map<String, Object> profile(@PathVariable long id, @RequestParam long assetId,
                                       @RequestParam String interval, @RequestParam String column,
                                       @RequestParam(required = false) Integer horizon) {
        return featureProfileService.profile(id, assetId, interval, column, horizon);
    }

    // ---------- 종목 관리 ----------

    @PatchMapping("/assets/{id}")
    @Transactional
    public Map<String, Object> updateAsset(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        var asset = assetRepository.findById(id).orElseThrow(() -> ApiException.notFound("종목이 없습니다."));
        asset.changeEnabled(Boolean.TRUE.equals(body.get("enabled")));
        return Map.of("id", id, "enabled", asset.isEnabled());
    }

    @PatchMapping("/markets/{id}")
    @Transactional
    public Map<String, Object> updateMarket(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        var market = marketRepository.findById(id).orElseThrow(() -> ApiException.notFound("마켓이 없습니다."));
        market.changeEnabled(Boolean.TRUE.equals(body.get("enabled")));
        return Map.of("id", id, "enabled", market.isEnabled());
    }
}
