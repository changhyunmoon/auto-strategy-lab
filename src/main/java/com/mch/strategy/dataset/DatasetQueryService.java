package com.mch.strategy.dataset;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.common.TimeUtils;
import com.mch.strategy.dataset.DatasetViews.AssetOption;
import com.mch.strategy.dataset.DatasetViews.ColumnView;
import com.mch.strategy.dataset.DatasetViews.Detail;
import com.mch.strategy.dataset.DatasetViews.GapView;
import com.mch.strategy.dataset.DatasetViews.QualityView;
import com.mch.strategy.dataset.DatasetViews.Summary;
import com.mch.strategy.feature.FeatureDefinition;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.instrument.Market;
import com.mch.strategy.instrument.MarketRepository;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;

import lombok.RequiredArgsConstructor;

/** 데이터세트 목록 · 상세 · 미리보기 조회 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DatasetQueryService {

    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final DatasetQualityRepository qualityRepository;
    private final DatasetRowRepository rowRepository;
    private final AssetRepository assetRepository;
    private final MarketRepository marketRepository;
    private final JobRepository jobRepository;
    private final FeatureRegistry featureRegistry;

    public List<Summary> list() {
        Map<Long, String> codes = assetCodes();
        return datasetRepository.findAllByOrderByIdDesc().stream().map(d -> summary(d, codes)).toList();
    }

    public List<Summary> readyList() {
        Map<Long, String> codes = assetCodes();
        return datasetRepository.findByStatusOrderByIdDesc(DatasetStatus.READY).stream().map(d -> summary(d, codes)).toList();
    }

    public Dataset get(long id) {
        return datasetRepository.findById(id).orElseThrow(() -> ApiException.notFound("데이터세트가 없습니다: " + id));
    }

    public List<DatasetColumn> columns(long id) {
        return columnRepository.findByDatasetIdOrderByColumnIndexAsc(id);
    }

    public Detail detail(long id) {
        Dataset d = get(id);
        Map<Long, String> codes = assetCodes();
        Map<Long, Market> markets = marketRepository.findAll().stream().collect(Collectors.toMap(Market::getId, Function.identity()));
        Map<String, FeatureDefinition> features = featureRegistry.all().stream()
                .collect(Collectors.toMap(FeatureDefinition::code, Function.identity()));

        List<ColumnView> columns = columns(id).stream().map(c -> {
            if (c.isBar()) {
                return new ColumnView(c.getColumnIndex(), c.getColumnName(), c.getFeatureCode(), "봉 값", "-", "");
            }
            FeatureDefinition f = features.get(c.getFeatureCode());
            return new ColumnView(c.getColumnIndex(), c.getColumnName(), c.getFeatureCode(),
                    f == null ? c.getFeatureCode() : f.name(),
                    f == null ? "?" : f.group().getCode(),
                    f == null ? "" : f.paramText());
        }).toList();

        List<QualityView> qualities = new ArrayList<>();
        List<GapView> gaps = new ArrayList<>();
        for (DatasetQuality q : qualityRepository.findByDatasetIdOrderByMarketIdAsc(id)) {
            String label = marketLabel(markets.get(q.getMarketId()));
            double fill = q.getExpectedMinutes() == 0 ? 0 : q.getActualMinutes() * 100.0 / q.getExpectedMinutes();
            qualities.add(new QualityView(label, q.getExpectedMinutes(), q.getActualMinutes(),
                    String.format("%.2f%%", fill), q.getNotes(), q.getActualMinutes() == q.getExpectedMinutes()));
            if (q.getGaps() != null) {
                for (Map<String, Object> g : q.getGaps()) {
                    gaps.add(new GapView(label,
                            TimeUtils.toKstText(Instant.parse(String.valueOf(g.get("from")))),
                            TimeUtils.toKstText(Instant.parse(String.valueOf(g.get("to"))).minusSeconds(60)),
                            ((Number) g.get("minutes")).longValue()));
                }
            }
        }
        gaps.sort((a, b) -> Long.compare(b.minutes(), a.minutes()));

        List<String> sourceLabels = new ArrayList<>();
        for (String s : d.getSources()) {
            List<String> symbols = d.getAssetIds().stream()
                    .flatMap(aid -> markets.values().stream()
                            .filter(m -> m.getExchange().name().equals(s) && m.getAsset().getId().equals(aid)))
                    .map(Market::getSymbol).toList();
            sourceLabels.add(com.mch.strategy.instrument.Exchange.valueOf(s).getLabel() + " " + String.join(" · ", symbols));
        }

        String duration = jobRepository.findFirstByDatasetIdOrderByIdDesc(id)
                .filter(j -> j.getStartedAt() != null && j.getFinishedAt() != null)
                .map(j -> formatDuration(Duration.between(j.getStartedAt(), j.getFinishedAt())))
                .orElse("");

        LocalDate fromDate = LocalDate.ofInstant(d.getRangeFrom(), ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(d.getRangeTo(), ZoneOffset.UTC).minusDays(1);
        return new Detail(
                summary(d, codes),
                TimeUtils.toKstText(d.getRangeFrom()) + " ~ " + TimeUtils.toKstText(d.getRangeTo().minusSeconds(60)),
                d.getWarmupFrom() == null ? "" : TimeUtils.toKstText(d.getWarmupFrom()),
                sourceLabels,
                columns,
                (int) columns.stream().filter(c -> !"bar".equals(c.featureCode())).count(),
                qualities,
                gaps,
                d.getAssetIds().stream().map(aid -> new AssetOption(aid, codes.getOrDefault(aid, "?"))).toList(),
                d.getIntervals(),
                duration,
                d.getAssetIds(),
                d.getSources(),
                columns.stream().map(ColumnView::featureCode).filter(c -> !"bar".equals(c)).distinct().toList(),
                fromDate.toString(),
                toDate.toString());
    }

    /** 미리보기 표 한 페이지 */
    public Map<String, Object> rows(long id, long assetId, String interval, boolean warmup, int page, int size) {
        get(id);
        Intervals.minutes(interval);
        int safeSize = Math.min(Math.max(size, 1), 200);
        long total = rowRepository.count(id, assetId, interval, warmup);
        List<Map<String, Object>> rows = rowRepository.page(id, assetId, interval, warmup, (long) page * safeSize, safeSize)
                .stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("time", TimeUtils.toKstText(Instant.ofEpochMilli(r.time())));
                    m.put("warmup", r.warmup());
                    m.put("values", nanToNull(r.values()));
                    return m;
                }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns(id).stream().map(DatasetColumn::getColumnName).toList());
        out.put("total", total);
        out.put("page", page);
        out.put("size", safeSize);
        out.put("rows", rows);
        return out;
    }

    Summary summary(Dataset d, Map<Long, String> codes) {
        Job job = jobRepository.findFirstByDatasetIdOrderByIdDesc(d.getId()).orElse(null);
        LocalDate fromDate = LocalDate.ofInstant(d.getRangeFrom(), ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(d.getRangeTo(), ZoneOffset.UTC).minusDays(1);
        return new Summary(
                d.getId(), d.getName(), d.getStatus().name(), d.getStatus().getLabel(), d.isBuilding(),
                d.getAssetIds().stream().map(aid -> codes.getOrDefault(aid, "?")).toList(),
                fromDate + " ~ " + toDate,
                d.getIntervals().stream().map(Intervals::label).toList(),
                d.getColumnCount(), d.getRowCount(),
                TimeUtils.toKstText(d.getCreatedAt()),
                job == null ? null : job.getId(),
                job == null ? 0 : job.getProgress(),
                job == null || job.getStep() == null ? "" : job.getStep(),
                d.getMessage() != null ? d.getMessage() : job == null || job.getMessage() == null ? "" : job.getMessage());
    }

    private Map<Long, String> assetCodes() {
        return assetRepository.findAll().stream().collect(Collectors.toMap(Asset::getId, Asset::getCode));
    }

    private static String marketLabel(Market m) {
        return m == null ? "?" : m.getExchange().getLabel() + " " + m.getSymbol();
    }

    private static String formatDuration(Duration d) {
        long s = d.getSeconds();
        return s >= 60 ? (s / 60) + "분 " + (s % 60) + "초" : s + "초";
    }

    private static List<Double> nanToNull(double[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (double v : values) {
            out.add(Double.isNaN(v) || Double.isInfinite(v) ? null : v);
        }
        return out;
    }
}
