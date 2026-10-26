package com.mch.strategy.trainset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mch.strategy.common.AsyncConfigNames;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.common.config.MlPaths;
import com.mch.strategy.dataset.DatasetColumn;
import com.mch.strategy.dataset.DatasetColumnRepository;
import com.mch.strategy.dataset.DatasetRowRepository;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.job.JobService;
import com.mch.strategy.trainset.TrainingSet.Split;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기준 데이터세트의 행을 읽어 학습 데이터 파일을 만든다 (백그라운드).
 * 자산마다: 판단 간격 행 읽기 → 다른 간격의 완성 봉 붙이기 → 정답 · 구간 계산 → 파일에 이어 쓰기.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingSetBuilder {

    private static final String[] SPLIT_KEYS = {"train", "val", "test"};

    private final TrainingSetRepository repository;
    private final TrainingSetStateService state;
    private final DatasetColumnRepository columnRepository;
    private final DatasetRowRepository rowRepository;
    private final AssetRepository assetRepository;
    private final FeatureRegistry featureRegistry;
    private final JobService jobService;
    private final MlPaths paths;
    private final ObjectMapper objectMapper;

    @Async(AsyncConfigNames.JOB_EXECUTOR)
    public void build(long trainingSetId, long jobId) {
        Path dir = paths.trainingSetDir(trainingSetId);
        try {
            jobService.start(jobId);
            state.building(trainingSetId);
            TrainingSet ts = repository.findById(trainingSetId).orElseThrow();
            Result result = write(ts, dir, jobId);
            state.ready(trainingSetId, result.rows, result.columns, result.stats);
            jobService.succeed(jobId);
            log.info("학습 데이터 {} 완료: {}행 · {}열", trainingSetId, result.rows, result.columns.size());
        } catch (Exception e) {
            log.error("학습 데이터 {} 실패", trainingSetId, e);
            MlPaths.deleteTree(dir);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            state.fail(trainingSetId, message);
            jobService.fail(jobId, message);
        }
    }

    private Result write(TrainingSet ts, Path dir, long jobId) throws IOException {
        MlPaths.deleteTree(dir);
        Files.createDirectories(dir);
        LabelSpec spec = ts.labelSpec();
        List<LabelSpec> alternatives = spec.alternatives();

        List<DatasetColumn> all = columnRepository.findByDatasetIdOrderByColumnIndexAsc(ts.getDatasetId());
        if (all.isEmpty()) {
            throw new IllegalStateException("기준 데이터세트(" + ts.getDatasetName() + ")가 지워졌거나 열이 없습니다.");
        }
        List<DatasetColumn> selected = ColumnSelection.select(all, featureRegistry, Set.copyOf(ts.getFeatureGroups()),
                ts.isIncludeBarColumns());
        List<DatasetColumn> extraCols = ColumnSelection.featuresOnly(selected);
        int closeIndex = ColumnSelection.barIndex(all, "close");
        int highIndex = ColumnSelection.barIndex(all, "high");
        int lowIndex = ColumnSelection.barIndex(all, "low");

        List<String> columnNames = new ArrayList<>();
        selected.forEach(c -> columnNames.add(c.getColumnName()));
        for (String iv : ts.getExtraIntervals()) {
            extraCols.forEach(c -> columnNames.add(c.getColumnName() + "@" + iv));
        }

        long decisionMs = Intervals.minutes(ts.getDecisionInterval()) * 60_000L;
        long[][] ranges = new long[3][];
        for (Split s : Split.values()) {
            Instant[] r = ts.range(s);
            ranges[s.ordinal()] = new long[]{r[0].toEpochMilli(), r[1].toEpochMilli()};
        }

        Map<Long, String> codes = new LinkedHashMap<>();
        for (Asset a : assetRepository.findAllById(ts.getAssetIds())) {
            codes.put(a.getId(), a.getCode());
        }
        Stats stats = new Stats(ts.getAssetIds().stream().map(id -> codes.getOrDefault(id, "?")).toList(),
                ts.getExtraIntervals(), alternatives, spec);

        long written = 0;
        try (BinaryColumnWriter fx = new BinaryColumnWriter(dir.resolve(TrainingFiles.FEATURES));
             BinaryColumnWriter fy = new BinaryColumnWriter(dir.resolve(TrainingFiles.LABEL));
             BinaryColumnWriter fs = new BinaryColumnWriter(dir.resolve(TrainingFiles.SPLIT));
             BinaryColumnWriter fa = new BinaryColumnWriter(dir.resolve(TrainingFiles.ASSET));
             BinaryColumnWriter ft = new BinaryColumnWriter(dir.resolve(TrainingFiles.TIME));
             BinaryColumnWriter ff = new BinaryColumnWriter(dir.resolve(TrainingFiles.FORWARD));
             BinaryColumnWriter fp = new BinaryColumnWriter(dir.resolve(TrainingFiles.PREVIOUS));
             BinaryColumnWriter fc = new BinaryColumnWriter(dir.resolve(TrainingFiles.CLOSE));
             BinaryColumnWriter fth = new BinaryColumnWriter(dir.resolve(TrainingFiles.THRESHOLD))) {

            int assetCount = ts.getAssetIds().size();
            for (int a = 0; a < assetCount; a++) {
                long assetId = ts.getAssetIds().get(a);
                String code = codes.getOrDefault(assetId, "?");
                int base = a * 100 / assetCount;
                int span = 100 / assetCount;
                jobService.update(jobId, base, code + " 행 읽기", null);

                DatasetRowRepository.Rows rows = rowRepository.load(ts.getDatasetId(), assetId, ts.getDecisionInterval(), null, null);
                if (rows.size() == 0) {
                    throw new IllegalStateException(code + " " + ts.getDecisionInterval() + " 행이 기준 데이터세트에 없습니다.");
                }
                List<DatasetRowRepository.Rows> extraRows = new ArrayList<>();
                List<int[]> extraIndex = new ArrayList<>();
                for (String iv : ts.getExtraIntervals()) {
                    jobService.update(jobId, base + span / 4, code + " " + Intervals.label(iv) + " 봉 붙이기", null);
                    DatasetRowRepository.Rows er = rowRepository.load(ts.getDatasetId(), assetId, iv, null, null);
                    extraRows.add(er);
                    extraIndex.add(TrainingRows.alignCompleted(rows.time(), decisionMs, er.time(), Intervals.minutes(iv) * 60_000L));
                }

                jobService.update(jobId, base + span / 2, code + " 정답 · 구간 계산", null);
                double[] close = rows.column(closeIndex);
                TrainingRows.Calculator calc = new TrainingRows.Calculator(rows.time(), close,
                        rows.column(highIndex), rows.column(lowIndex), decisionMs);
                TrainingRows.Plan[] plan = TrainingRows.plan(calc, ranges, spec);

                for (int i = 0; i < plan.length; i++) {
                    TrainingRows.Plan p = plan[i];
                    if (p.split() < 0) {
                        stats.excluded(p.excludedReason());
                        continue;
                    }
                    double[] v = rows.values().get(i);
                    for (DatasetColumn c : selected) {
                        fx.writeFloat(value(v, c.getColumnIndex()));
                    }
                    for (int e = 0; e < extraRows.size(); e++) {
                        int j = extraIndex.get(e)[i];
                        if (j < 0) {
                            stats.extraMissing(e);
                        }
                        double[] ev = j < 0 ? null : extraRows.get(e).values().get(j);
                        for (DatasetColumn c : extraCols) {
                            fx.writeFloat(ev == null ? Double.NaN : value(ev, c.getColumnIndex()));
                        }
                    }
                    fy.writeByte(p.label());
                    fs.writeByte(p.split());
                    fa.writeByte(a);
                    ft.writeLong(rows.time()[i]);
                    ff.writeFloat(p.forwardPct());
                    fp.writeFloat(p.previousLabel());
                    fc.writeFloat(close[i]);
                    fth.writeFloat(p.thresholdPct());
                    stats.row(p, a, rows.time()[i], calc, i);
                    written++;
                }
                jobService.update(jobId, base + span, code + " 완료", null);
            }
        }
        if (written == 0) {
            throw new IllegalStateException("조건에 맞는 행이 없습니다. 날짜 구간과 기준 데이터세트 기간을 확인하세요.");
        }
        for (int s = 0; s < 2; s++) {
            if (stats.rows[s] == 0) {
                throw new IllegalStateException(Split.values()[s].getLabel() + " 구간에 행이 없습니다.");
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("format", TrainingFiles.FORMAT);
        meta.put("trainingSetId", ts.getId());
        meta.put("rows", written);
        meta.put("columns", columnNames);
        meta.put("assets", stats.assets);
        meta.put("labels", List.of("하락", "중립", "상승"));
        meta.put("label", spec.toMap());
        meta.put("decisionInterval", ts.getDecisionInterval());
        meta.put("decisionMinutes", Intervals.minutes(ts.getDecisionInterval()));
        meta.put("extraIntervals", ts.getExtraIntervals());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(TrainingFiles.META).toFile(), meta);

        return new Result(written, columnNames, stats.toMap());
    }

    private static double value(double[] row, int index) {
        return index < row.length ? row[index] : Double.NaN;
    }

    private record Result(long rows, List<String> columns, Map<String, Object> stats) {
    }

    /** 구간·자산별 행 수, 정답 분포(같은 방식의 다른 기준마다), 제외 사유, 기준 분포 */
    private static final class Stats {
        final List<String> assets;
        final List<String> extraIntervals;
        final List<LabelSpec> alternatives;
        final LabelSpec spec;
        final long[] rows = new long[3];
        final long[][] rowsByAsset;
        final long[] first = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        final long[] last = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        /** classCounts[split][asset][대안][class] — 최종 테스트는 집계하지 않는다 */
        final long[][][][] classCounts;
        /** 선택한 정답 기준의 학습·검증 구간 평균(%) · 동시 도달 수 */
        final double[] thresholdSum = new double[2];
        final long[] ambiguous = new long[2];
        final Map<String, Long> excluded = new LinkedHashMap<>();
        final long[] extraMissing;

        Stats(List<String> assets, List<String> extraIntervals, List<LabelSpec> alternatives, LabelSpec spec) {
            this.assets = assets;
            this.extraIntervals = extraIntervals;
            this.alternatives = alternatives;
            this.spec = spec;
            this.rowsByAsset = new long[3][assets.size()];
            this.classCounts = new long[2][assets.size()][alternatives.size()][3];
            this.extraMissing = new long[extraIntervals.size()];
            for (String k : List.of(TrainingRows.OUTSIDE, TrainingRows.NO_NEXT_BAR, TrainingRows.CROSSES_BOUNDARY,
                    TrainingRows.MISSING_PRICE, TrainingRows.NO_VOLATILITY)) {
                excluded.put(k, 0L);
            }
        }

        void excluded(String reason) {
            excluded.merge(reason, 1L, Long::sum);
        }

        void extraMissing(int e) {
            extraMissing[e]++;
        }

        void row(TrainingRows.Plan p, int asset, long time, TrainingRows.Calculator calc, int i) {
            int split = p.split();
            rows[split]++;
            rowsByAsset[split][asset]++;
            first[split] = Math.min(first[split], time);
            last[split] = Math.max(last[split], time);
            if (split < 2) {
                thresholdSum[split] += p.thresholdPct();
                if (p.ambiguous()) {
                    ambiguous[split]++;
                }
                for (int t = 0; t < alternatives.size(); t++) {
                    int l = alternatives.get(t).sameAlternative(spec) ? p.label() : calc.labelFor(i, alternatives.get(t));
                    if (l >= 0) {
                        classCounts[split][asset][t][l]++;
                    }
                }
            }
        }

        Map<String, Object> toMap() {
            List<String> altKeys = alternatives.stream().map(LabelSpec::alternativeLabel).toList();
            Map<String, Object> splits = new LinkedHashMap<>();
            for (int s = 0; s < 3; s++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rows", rows[s]);
                m.put("first", rows[s] == 0 ? null : Instant.ofEpochMilli(first[s]).toString());
                m.put("last", rows[s] == 0 ? null : Instant.ofEpochMilli(last[s]).toString());
                Map<String, Long> byAsset = new LinkedHashMap<>();
                for (int a = 0; a < assets.size(); a++) {
                    byAsset.put(assets.get(a), rowsByAsset[s][a]);
                }
                m.put("byAsset", byAsset);
                if (s < 2) {
                    // classes[asset 또는 "ALL"][대안 이름] = [하락, 중립, 상승]
                    Map<String, Map<String, long[]>> classes = new LinkedHashMap<>();
                    Map<String, long[]> total = new LinkedHashMap<>();
                    for (int t = 0; t < alternatives.size(); t++) {
                        long[] sum = new long[3];
                        for (int a = 0; a < assets.size(); a++) {
                            for (int c = 0; c < 3; c++) {
                                sum[c] += classCounts[s][a][t][c];
                            }
                        }
                        total.put(altKeys.get(t), sum);
                    }
                    classes.put("ALL", total);
                    for (int a = 0; a < assets.size(); a++) {
                        Map<String, long[]> byT = new LinkedHashMap<>();
                        for (int t = 0; t < alternatives.size(); t++) {
                            byT.put(altKeys.get(t), classCounts[s][a][t].clone());
                        }
                        classes.put(assets.get(a), byT);
                    }
                    m.put("classes", classes);
                    m.put("meanThresholdPct", rows[s] == 0 ? null : thresholdSum[s] / rows[s]);
                    m.put("ambiguous", ambiguous[s]);
                }
                splits.put(SPLIT_KEYS[s], m);
            }
            Map<String, Long> missing = new LinkedHashMap<>();
            for (int e = 0; e < extraIntervals.size(); e++) {
                missing.put(extraIntervals.get(e), extraMissing[e]);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assets", assets);
            out.put("alternatives", altKeys);
            out.put("selected", spec.alternativeLabel());
            out.put("splits", splits);
            out.put("excluded", excluded);
            out.put("extraMissing", missing);
            return out;
        }
    }
}
