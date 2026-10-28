package com.mch.strategy.web;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.mch.strategy.collect.CandleRepository;
import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.TimeUtils;
import com.mch.strategy.feature.FeaturePreset;
import com.mch.strategy.feature.FeatureService;
import com.mch.strategy.indicator.IndicatorConfig;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.JobCancels;
import com.mch.strategy.job.JobService;
import com.mch.strategy.label.LabelService;
import com.mch.strategy.label.LabelSummary;
import com.mch.strategy.raw.RawService;
import com.mch.strategy.split.SplitPlan;
import com.mch.strategy.split.SplitService;
import com.mch.strategy.split.SplitSummary;

import lombok.RequiredArgsConstructor;

/** V2 JSON API. 화면의 jQuery 가 부른다. 오류는 {"message": "..."} 로 돌려준다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

    private final RawService rawService;
    private final FeatureService featureService;
    private final LabelService labelService;
    private final SplitService splitService;
    private final JobService jobService;
    private final JobCancels jobCancels;
    private final CandleRepository candleRepository;
    private final MarketRepository marketRepository;
    private final AssetRepository assetRepository;

    // ---------------------------------------------------------------- 공통

    /** 거래쌍별로 DB 에 이미 있는 1분봉 기간 (KST 날짜) */
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

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable long id) {
        return jobService.view(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancelJob(@PathVariable long id) {
        Map<String, Object> job = jobService.view(id);
        if (!"RUNNING".equals(job.get("status"))) {
            throw ApiException.badRequest("이미 끝난 작업입니다.");
        }
        jobCancels.request(id);
        return Map.of("id", id, "requested", true);
    }

    // ---------------------------------------------------------------- ① 원본

    @PostMapping("/raw")
    public RawService.Created createRaw(@RequestBody RawService.CreateRequest request) {
        return rawService.create(request);
    }

    @DeleteMapping("/raw/{id}")
    public Map<String, Object> deleteRaw(@PathVariable long id) {
        if (!featureService.byRaw(id).isEmpty()) {
            throw ApiException.badRequest("이 원본으로 만든 지표 데이터셋이 있어 지울 수 없습니다. 지표 데이터셋을 먼저 지우세요.");
        }
        rawService.delete(id);
        return Map.of("deleted", id);
    }

    // ---------------------------------------------------------------- ② 지표

    public record PlanRequest(IndicatorConfig config, Long rawId) {
    }

    @PostMapping("/features/plan")
    public FeatureService.PlanView plan(@RequestBody PlanRequest request) {
        if (request.config() == null) throw ApiException.badRequest("지표 설정이 없습니다.");
        return featureService.plan(request.config(), request.rawId());
    }

    @PostMapping("/features")
    public FeatureService.Created createFeature(@RequestBody FeatureService.CreateRequest request) {
        return featureService.create(request);
    }

    @DeleteMapping("/features/{id}")
    public Map<String, Object> deleteFeature(@PathVariable long id) {
        if (!labelService.byFeature(id).isEmpty()) {
            throw ApiException.badRequest("이 지표 데이터셋으로 만든 정답이 있어 지울 수 없습니다. 정답을 먼저 지우세요.");
        }
        featureService.delete(id);
        return Map.of("deleted", id);
    }

    @GetMapping("/features/{id}/rows")
    public FeatureService.RowsView featureRows(@PathVariable long id, @RequestParam long marketId,
                                               @RequestParam(defaultValue = "0") int offset,
                                               @RequestParam(defaultValue = "50") int limit,
                                               @RequestParam(required = false) List<String> cols,
                                               @RequestParam(required = false) String kind,
                                               @RequestParam(required = false) String at) {
        return featureService.rows(id, marketId, offset, limit, cols, kind, at);
    }

    @GetMapping("/features/{id}/export.csv")
    public ResponseEntity<StreamingResponseBody> exportFeature(@PathVariable long id, @RequestParam long marketId) {
        featureService.get(id);
        StreamingResponseBody body = out -> {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            try {
                featureService.exportCsv(id, marketId, w);
            } catch (IOException e) {
                throw e;
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"features_" + id + "_market" + marketId + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/feature-presets")
    public List<Map<String, Object>> presets() {
        return featureService.presets();
    }

    public record PresetRequest(String name, IndicatorConfig config) {
    }

    @PostMapping("/feature-presets")
    public Map<String, Object> savePreset(@RequestBody PresetRequest request) {
        if (request.config() == null) throw ApiException.badRequest("지표 설정이 없습니다.");
        FeaturePreset p = featureService.savePreset(request.name(), request.config());
        return Map.of("id", p.getId(), "name", p.getName());
    }

    @DeleteMapping("/feature-presets/{id}")
    public Map<String, Object> deletePreset(@PathVariable long id) {
        featureService.deletePreset(id);
        return Map.of("deleted", id);
    }

    // ---------------------------------------------------------------- ③ 정답

    @PostMapping("/labels/preview")
    public LabelSummary previewLabel(@RequestBody LabelService.PreviewRequest request) {
        return labelService.preview(request);
    }

    @PostMapping("/labels")
    public LabelService.Created createLabel(@RequestBody LabelService.CreateRequest request) {
        return labelService.create(request);
    }

    @DeleteMapping("/labels/{id}")
    public Map<String, Object> deleteLabel(@PathVariable long id) {
        if (!splitService.byLabel(id).isEmpty()) {
            throw ApiException.badRequest("이 정답으로 만든 분할 계획이 있어 지울 수 없습니다. 분할 계획을 먼저 지우세요.");
        }
        labelService.delete(id);
        return Map.of("deleted", id);
    }

    @GetMapping("/labels/{id}/rows")
    public Map<String, Object> labelRows(@PathVariable long id, @RequestParam long marketId,
                                         @RequestParam(defaultValue = "0") int offset,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(required = false) String status) {
        return labelService.rows(id, marketId, offset, limit, status);
    }

    // ---------------------------------------------------------------- ④ 분할

    @PostMapping("/splits/preview")
    public SplitSummary previewSplit(@RequestBody SplitService.PreviewRequest request) {
        return splitService.preview(request);
    }

    @PostMapping("/splits")
    public Map<String, Object> createSplit(@RequestBody SplitService.CreateRequest request) {
        SplitPlan p = splitService.create(request);
        return Map.of("id", p.getId(), "status", p.getStatus().name());
    }

    @DeleteMapping("/splits/{id}")
    public Map<String, Object> deleteSplit(@PathVariable long id) {
        splitService.delete(id);
        return Map.of("deleted", id);
    }

    @PostMapping("/splits/{id}/reveal-test")
    public Map<String, Object> revealTest(@PathVariable long id) {
        return splitService.revealTest(id);
    }

    // ---------------------------------------------------------------- 종목 관리

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
