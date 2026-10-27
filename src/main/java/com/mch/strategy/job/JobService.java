package com.mch.strategy.job;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mch.strategy.common.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * 작업 진행률 기록. 백그라운드 스레드에서 호출되므로 호출마다 바로 저장(커밋)한다.
 * 「중지」 요청이 있으면 start · update 에서 {@link JobCancels.CancelledException} 을 던진다.
 * 그래서 진행률을 알리는 모든 작업은 따로 확인하지 않아도 다음 진행률 갱신 때 멈춘다.
 */
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final JobCancels cancels;

    /** 대기열에서 차례가 왔을 때. 기다리는 동안 중지했으면 시작하지 않고 멈춘다 */
    @Transactional
    public void start(long jobId) {
        cancels.check(jobId);
        get(jobId).start();
    }

    @Transactional
    public void update(long jobId, int progress, String step, String message) {
        cancels.check(jobId);
        get(jobId).update(progress, step, message);
    }

    @Transactional
    public void succeed(long jobId) {
        cancels.clear(jobId);
        get(jobId).succeed();
    }

    @Transactional
    public void fail(long jobId, String message) {
        cancels.clear(jobId);
        get(jobId).fail(message);
    }

    /**
     * 화면용 작업 상태. waiting = 아직 시작 전(앞 작업이 끝나기를 기다림), ahead = 이 작업보다 먼저 처리될 작업 수.
     * 작업은 한 번에 하나씩, 만든 순서대로 처리한다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> view(long jobId) {
        Job job = get(jobId);
        boolean waiting = job.getStatus() == JobStatus.RUNNING && job.getStartedAt() == null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.getId());
        m.put("datasetId", job.getDatasetId() == null ? 0 : job.getDatasetId());
        m.put("targetId", job.getTargetId() == null ? 0 : job.getTargetId());
        m.put("type", job.getJobType());
        m.put("status", job.getStatus().name());
        m.put("progress", job.getProgress());
        m.put("step", job.getStep() == null ? "" : job.getStep());
        m.put("message", job.getMessage() == null ? "" : job.getMessage());
        m.put("waiting", waiting);
        m.put("ahead", waiting ? jobRepository.countByStatusAndIdLessThan(JobStatus.RUNNING, jobId) : 0);
        m.put("cancelRequested", cancels.isRequested(jobId));
        return m;
    }

    private Job get(long jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("작업이 없습니다: " + jobId));
    }
}
