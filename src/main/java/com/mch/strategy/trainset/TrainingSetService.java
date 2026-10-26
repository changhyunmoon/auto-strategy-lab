package com.mch.strategy.trainset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.Intervals;
import com.mch.strategy.common.config.MlPaths;
import com.mch.strategy.dataset.Dataset;
import com.mch.strategy.dataset.DatasetColumnRepository;
import com.mch.strategy.dataset.DatasetRepository;
import com.mch.strategy.dataset.DatasetStatus;
import com.mch.strategy.feature.FeatureGroup;
import com.mch.strategy.feature.FeatureRegistry;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.training.ModelRunService;

import lombok.RequiredArgsConstructor;

/** 학습 데이터 만들기 요청 검증 · 저장 · 삭제 */
@Service
@RequiredArgsConstructor
public class TrainingSetService {

    private final TrainingSetRepository repository;
    private final DatasetRepository datasetRepository;
    private final DatasetColumnRepository columnRepository;
    private final FeatureRegistry featureRegistry;
    private final JobRepository jobRepository;
    private final TrainingSetBuilder builder;
    private final ModelRunService modelRunService;
    private final MlPaths paths;
    private final TransactionTemplate tx;

    public Map<String, Long> create(CreateTrainingSetRequest r) {
        Dataset ds = datasetRepository.findById(r.datasetId())
                .orElseThrow(() -> ApiException.badRequest("기준 데이터세트가 없습니다."));
        if (ds.getStatus() != DatasetStatus.READY) {
            throw ApiException.badRequest("완료된 데이터세트만 고를 수 있습니다.");
        }
        if (!ds.getIntervals().contains(r.decisionInterval())) {
            throw ApiException.badRequest("데이터세트에 " + r.decisionInterval() + " 봉이 없습니다.");
        }
        int decisionMinutes = Intervals.minutes(r.decisionInterval());
        List<String> extras = r.extraIntervals() == null ? List.of() : Intervals.SUPPORTED.stream()
                .filter(r.extraIntervals()::contains).filter(iv -> !iv.equals(r.decisionInterval())).toList();
        for (String iv : extras) {
            if (!ds.getIntervals().contains(iv)) {
                throw ApiException.badRequest("데이터세트에 " + iv + " 봉이 없습니다.");
            }
            if (Intervals.minutes(iv) < decisionMinutes) {
                throw ApiException.badRequest("판단 간격보다 짧은 간격(" + Intervals.label(iv) + ")은 붙일 수 없습니다.");
            }
        }
        List<Long> assetIds = r.assetIds().stream().distinct().sorted().toList();
        if (!ds.getAssetIds().containsAll(assetIds)) {
            throw ApiException.badRequest("데이터세트에 없는 종목이 포함되어 있습니다.");
        }
        if (assetIds.size() > 100) {
            throw ApiException.badRequest("종목이 너무 많습니다.");
        }
        boolean thresholdOk = false;
        for (double t : TrainingRows.THRESHOLDS) {
            thresholdOk |= r.thresholdPct().compareTo(BigDecimal.valueOf(t)) == 0;
        }
        if (!thresholdOk) {
            throw ApiException.badRequest("정답 기준은 0.1 · 0.3 · 0.5 · 0.7 · 1.0 % 중에서 고르세요.");
        }
        List<String> groups = java.util.Arrays.stream(FeatureGroup.values()).map(FeatureGroup::getCode)
                .filter(r.featureGroups()::contains).toList();
        if (ColumnSelection.select(columnRepository.findByDatasetIdOrderByColumnIndexAsc(ds.getId()),
                featureRegistry, Set.copyOf(groups), r.includeBarColumns()).isEmpty()) {
            throw ApiException.badRequest("고른 그룹에 해당하는 지표 열이 데이터세트에 없습니다.");
        }
        validateDates(r, ds);

        long[] ids = tx.execute(status -> {
            TrainingSet ts = repository.save(TrainingSet.create(r, ds.getName(), assetIds, extras, groups));
            Job job = jobRepository.save(Job.forTarget(Job.TYPE_TRAINSET_BUILD, ts.getId()));
            return new long[]{ts.getId(), job.getId()};
        });
        builder.build(ids[0], ids[1]);
        return Map.of("trainingSetId", ids[0], "jobId", ids[1]);
    }

    /** 학습 데이터와 그 데이터로 만든 실험을 모두 지운다 (파일 포함). */
    public void delete(long id) {
        TrainingSet ts = repository.findById(id).orElseThrow(() -> ApiException.notFound("학습 데이터가 없습니다."));
        if (ts.isBuilding()) {
            throw ApiException.badRequest("만드는 중인 학습 데이터는 끝난 뒤에 지울 수 있습니다.");
        }
        modelRunService.deleteAllOf(id);
        MlPaths.deleteTree(paths.trainingSetDir(id));
        repository.deleteById(id);
    }

    private static void validateDates(CreateTrainingSetRequest r, Dataset ds) {
        LocalDate first = LocalDate.ofInstant(ds.getRangeFrom(), ZoneOffset.UTC);
        LocalDate last = LocalDate.ofInstant(ds.getRangeTo(), ZoneOffset.UTC).minusDays(1);
        LocalDate[][] periods = {{r.trainFrom(), r.trainTo()}, {r.valFrom(), r.valTo()}, {r.testFrom(), r.testTo()}};
        String[] names = {"학습", "검증", "테스트"};
        for (int i = 0; i < 3; i++) {
            if (periods[i][0].isAfter(periods[i][1])) {
                throw ApiException.badRequest(names[i] + " 시작일이 종료일보다 늦습니다.");
            }
            if (periods[i][0].isBefore(first) || periods[i][1].isAfter(last)) {
                throw ApiException.badRequest(names[i] + " 기간이 데이터세트 범위(" + first + " ~ " + last + ")를 벗어납니다.");
            }
        }
        if (!r.trainTo().isBefore(r.valFrom())) {
            throw ApiException.badRequest("검증 기간은 학습 기간이 끝난 다음 날부터 시작해야 합니다.");
        }
        if (!r.valTo().isBefore(r.testFrom())) {
            throw ApiException.badRequest("테스트 기간은 검증 기간이 끝난 다음 날부터 시작해야 합니다.");
        }
    }
}
