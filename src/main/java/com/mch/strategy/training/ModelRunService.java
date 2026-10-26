package com.mch.strategy.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mch.strategy.common.ApiException;
import com.mch.strategy.common.config.MlPaths;
import com.mch.strategy.job.Job;
import com.mch.strategy.job.JobRepository;
import com.mch.strategy.trainset.TrainingSet;
import com.mch.strategy.trainset.TrainingSetRepository;
import com.mch.strategy.trainset.TrainingSetStatus;

import lombok.RequiredArgsConstructor;

/** 실험 만들기(모델마다 하나) · 지우기 · 최종 테스트 요청 */
@Service
@RequiredArgsConstructor
public class ModelRunService {

    private final ModelRunRepository repository;
    private final TrainingSetRepository trainingSetRepository;
    private final JobRepository jobRepository;
    private final ModelRunner runner;
    private final MlPaths paths;
    private final TransactionTemplate tx;

    public Map<String, Object> create(CreateRunsRequest r) {
        TrainingSet ts = trainingSetRepository.findById(r.trainingSetId())
                .orElseThrow(() -> ApiException.badRequest("학습 데이터가 없습니다."));
        if (ts.getStatus() != TrainingSetStatus.READY) {
            throw ApiException.badRequest("완료된 학습 데이터만 고를 수 있습니다.");
        }
        if (r.models().size() > 30) {
            throw ApiException.badRequest("한 번에 30개까지 실행할 수 있습니다.");
        }
        List<ModelRun> runs = new ArrayList<>();
        boolean many = r.models().size() > 1;
        for (CreateRunsRequest.ModelChoice m : r.models()) {
            Map<String, Object> params = m.type().resolve(m.params());
            RunOptions.Balance balance = r.balance();
            if (m.type().isBaseline() || (balance == RunOptions.Balance.CLASS_WEIGHT && !m.type().supportsClassWeight())) {
                balance = RunOptions.Balance.NONE; // 지원하지 않는 모델은 적용하지 않고 「미적용」으로 기록한다
            }
            String name = many ? r.name().trim() + " · " + m.type().getLabel() : r.name().trim();
            runs.add(ModelRun.create(name, ts.getId(), m.type(), params, r.trainUnit(), balance, r.earlyStopping(),
                    r.seed(), r.maxTrainRows(), r.permutationImportance(), blankToNull(r.memo())));
        }
        List<long[]> ids = tx.execute(status -> {
            List<long[]> out = new ArrayList<>();
            for (ModelRun run : repository.saveAll(runs)) {
                Job job = jobRepository.save(Job.forTarget(Job.TYPE_MODEL_TRAIN, run.getId()));
                out.add(new long[]{run.getId(), job.getId()});
            }
            return out;
        });
        ids.forEach(p -> runner.train(p[0], p[1])); // 커밋 후 차례로 실행
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runIds", ids.stream().map(p -> p[0]).toList());
        out.put("trainingSetId", ts.getId());
        return out;
    }

    /** 저장한 모델로 최종 테스트 구간을 평가한다. 실행할 때마다 횟수 · 시각이 기록된다. */
    public Map<String, Object> startTest(long runId) {
        ModelRun run = repository.findById(runId).orElseThrow(() -> ApiException.notFound("실험이 없습니다."));
        if (run.getStatus() != ModelRunStatus.READY) {
            throw ApiException.badRequest("학습이 끝난 실험만 최종 테스트를 할 수 있습니다.");
        }
        if (run.getTestStatus() == ModelRun.TestStatus.RUNNING) {
            throw ApiException.badRequest("이미 최종 테스트를 실행하고 있습니다.");
        }
        long jobId = tx.execute(status -> {
            repository.findById(runId).ifPresent(ModelRun::testRunning);
            return jobRepository.save(Job.forTarget(Job.TYPE_MODEL_TEST, runId)).getId();
        });
        runner.test(runId, jobId);
        return Map.of("runId", runId, "jobId", jobId);
    }

    public void delete(long runId) {
        ModelRun run = repository.findById(runId).orElseThrow(() -> ApiException.notFound("실험이 없습니다."));
        if (run.isBusy()) {
            throw ApiException.badRequest("실행 중이거나 대기 중인 실험은 끝난 뒤에 지울 수 있습니다.");
        }
        MlPaths.deleteTree(paths.runDir(runId));
        repository.deleteById(runId);
    }

    /** 학습 데이터를 지울 때 그 데이터로 만든 실험도 모두 지운다. */
    public void deleteAllOf(long trainingSetId) {
        List<ModelRun> runs = repository.findByTrainingSetIdOrderByIdDesc(trainingSetId);
        if (runs.stream().anyMatch(ModelRun::isBusy)) {
            throw ApiException.badRequest("이 학습 데이터로 실행 중이거나 대기 중인 실험이 있습니다. 끝난 뒤에 지워 주세요.");
        }
        for (ModelRun run : runs) {
            MlPaths.deleteTree(paths.runDir(run.getId()));
        }
        repository.deleteAll(runs);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
