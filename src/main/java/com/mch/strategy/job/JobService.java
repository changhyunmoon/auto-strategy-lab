package com.mch.strategy.job;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mch.strategy.common.ApiException;

import lombok.RequiredArgsConstructor;

/** 작업 진행률 기록. 백그라운드 스레드에서 호출되므로 호출마다 바로 저장(커밋)한다. */
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;

    @Transactional
    public void start(long jobId) {
        get(jobId).start();
    }

    @Transactional
    public void update(long jobId, int progress, String step, String message) {
        get(jobId).update(progress, step, message);
    }

    @Transactional
    public void succeed(long jobId) {
        get(jobId).succeed();
    }

    @Transactional
    public void fail(long jobId, String message) {
        get(jobId).fail(message);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(long jobId) {
        Job job = get(jobId);
        return Map.of(
                "id", job.getId(),
                "datasetId", job.getDatasetId() == null ? 0 : job.getDatasetId(),
                "status", job.getStatus().name(),
                "progress", job.getProgress(),
                "step", job.getStep() == null ? "" : job.getStep(),
                "message", job.getMessage() == null ? "" : job.getMessage());
    }

    private Job get(long jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("작업이 없습니다: " + jobId));
    }
}
