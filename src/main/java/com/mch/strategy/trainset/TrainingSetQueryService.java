package com.mch.strategy.trainset;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.common.TimeUtils;
import com.mch.strategy.feature.FeatureGroup;
import com.mch.strategy.instrument.Asset;
import com.mch.strategy.instrument.AssetRepository;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.training.ModelRunRepository;
import com.mch.strategy.trainset.TrainingSetViews.Detail;
import com.mch.strategy.trainset.TrainingSetViews.Summary;

import lombok.RequiredArgsConstructor;

/** 학습 데이터 목록 · 상세 조회 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainingSetQueryService {

    private final TrainingSetRepository repository;
    private final AssetRepository assetRepository;
    private final JobRepository jobRepository;
    private final ModelRunRepository runRepository;

    public List<Summary> list() {
        Map<Long, String> codes = assetCodes();
        return repository.findAllByOrderByIdDesc().stream().map(t -> summary(t, codes)).toList();
    }

    public List<Summary> readyList() {
        Map<Long, String> codes = assetCodes();
        return repository.findByStatusOrderByIdDesc(TrainingSetStatus.READY).stream().map(t -> summary(t, codes)).toList();
    }

    public TrainingSet get(long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("학습 데이터가 없습니다: " + id));
    }

    public Detail detail(long id) {
        TrainingSet t = get(id);
        Map<String, String> groupLabels = Arrays.stream(FeatureGroup.values())
                .collect(Collectors.toMap(FeatureGroup::getCode, g -> g.getCode() + ". " + g.getLabel()));
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("datasetId", t.getDatasetId());
        copy.put("decisionInterval", t.getDecisionInterval());
        copy.put("extraIntervals", t.getExtraIntervals());
        copy.put("assetIds", t.getAssetIds());
        copy.put("featureGroups", t.getFeatureGroups());
        copy.put("includeBarColumns", t.isIncludeBarColumns());
        copy.put("label", t.labelSpec().toMap());
        copy.put("trainFrom", t.getTrainFrom().toString());
        copy.put("trainTo", t.getTrainTo().toString());
        copy.put("valFrom", t.getValFrom().toString());
        copy.put("valTo", t.getValTo().toString());
        copy.put("testFrom", t.getTestFrom().toString());
        copy.put("testTo", t.getTestTo().toString());
        return new Detail(
                summary(t, assetCodes()),
                t.isIncludeBarColumns(),
                t.getFeatureGroups().stream().map(g -> groupLabels.getOrDefault(g, g)).toList(),
                t.getColumns() == null ? List.of() : t.getColumns(),
                t.getStats() == null ? Map.of() : t.getStats(),
                copy);
    }

    Summary summary(TrainingSet t, Map<Long, String> codes) {
        Job job = jobRepository.findFirstByJobTypeAndTargetIdOrderByIdDesc(Job.TYPE_TRAINSET_BUILD, t.getId()).orElse(null);
        return new Summary(
                t.getId(), t.getName(), t.getStatus().name(), t.getStatus().getLabel(), t.isBuilding(),
                t.getDatasetId(), t.getDatasetName(),
                t.getAssetIds().stream().map(a -> codes.getOrDefault(a, "?")).toList(),
                Intervals.label(t.getDecisionInterval()),
                t.getExtraIntervals().stream().map(Intervals::label).toList(),
                t.getFeatureGroups(),
                t.getThresholdPct().stripTrailingZeros().toPlainString(),
                t.labelSpec().text(Intervals.label(t.getDecisionInterval())),
                t.labelSpec().horizon(),
                t.getTrainFrom() + " ~ " + t.getTrainTo(),
                t.getValFrom() + " ~ " + t.getValTo(),
                t.getTestFrom() + " ~ " + t.getTestTo(),
                t.getRowCount(), t.getColumnCount(),
                TimeUtils.toKstText(t.getCreatedAt()),
                job == null ? null : job.getId(),
                job == null ? 0 : job.getProgress(),
                job == null || job.getStep() == null ? "" : job.getStep(),
                t.getMessage() != null ? t.getMessage() : "",
                runRepository.countByTrainingSetId(t.getId()));
    }

    private Map<Long, String> assetCodes() {
        return assetRepository.findAll().stream().collect(Collectors.toMap(Asset::getId, Asset::getCode));
    }
}
