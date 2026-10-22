package com.mch.strategy.dataset;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mch.strategy.job.JobRepository;
import com.mch.strategy.job.JobStatus;

import lombok.RequiredArgsConstructor;

/** 백그라운드 작업이 데이터세트 상태를 바꿀 때 쓴다. 호출마다 바로 커밋된다. */
@Service
@RequiredArgsConstructor
public class DatasetStateService {

    private final DatasetRepository datasetRepository;
    private final DatasetQualityRepository qualityRepository;
    private final JobRepository jobRepository;

    @Transactional
    public void changeStatus(long datasetId, DatasetStatus status) {
        datasetRepository.findById(datasetId).ifPresent(d -> d.changeStatus(status));
    }

    @Transactional
    public void ready(long datasetId, long rowCount) {
        datasetRepository.findById(datasetId).ifPresent(d -> d.ready(rowCount));
    }

    @Transactional
    public void fail(long datasetId, String message) {
        datasetRepository.findById(datasetId).ifPresent(d -> d.fail(message));
    }

    @Transactional
    public void saveQuality(long datasetId, List<DatasetQuality> qualities) {
        qualityRepository.deleteByDatasetId(datasetId);
        qualityRepository.saveAll(qualities);
    }

    /**
     * 앱이 작업 도중 꺼졌다면 「진행 중」으로 남은 작업과 데이터세트를 실패로 표시만 한다.
     * 이어서 처리하지 않는다. 사용자가 다시 만들면 된다.
     */
    @Transactional
    public int failInterrupted() {
        jobRepository.findByStatus(JobStatus.RUNNING).forEach(j -> j.fail("앱이 종료되어 중단되었습니다."));
        List<Dataset> building = datasetRepository.findByStatusNotIn(Set.of(DatasetStatus.READY, DatasetStatus.FAILED));
        building.forEach(d -> d.fail("앱이 종료되어 중단되었습니다. 같은 설정으로 다시 만들어 주세요."));
        return building.size();
    }
}
