package com.mch.strategy.training;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 백그라운드 작업이 실험 상태를 바꿀 때 쓴다. 호출마다 바로 커밋된다. */
@Service
@RequiredArgsConstructor
public class ModelRunStateService {

    private final ModelRunRepository repository;

    @Transactional
    public void running(long id) {
        repository.findById(id).ifPresent(ModelRun::running);
    }

    @Transactional
    public void trained(long id, ModelRun.Scores train, ModelRun.Scores val, Double baselineMacroF1, double seconds) {
        repository.findById(id).ifPresent(r -> r.trained(train, val, baselineMacroF1, seconds));
    }

    @Transactional
    public void fail(long id, String message) {
        repository.findById(id).ifPresent(r -> r.fail(message));
    }

    @Transactional
    public void testRunning(long id) {
        repository.findById(id).ifPresent(ModelRun::testRunning);
    }

    @Transactional
    public void tested(long id, ModelRun.Scores test) {
        repository.findById(id).ifPresent(r -> r.tested(test));
    }

    @Transactional
    public void testFailed(long id, String message) {
        repository.findById(id).ifPresent(r -> r.testFailed(message));
    }

    /** 앱이 꺼지면서 끝나지 못한(또는 대기 중이던) 실험을 실패로 표시만 한다. */
    @Transactional
    public int failInterrupted() {
        List<ModelRun> runs = repository.findByStatusIn(Set.of(ModelRunStatus.PENDING, ModelRunStatus.RUNNING));
        runs.forEach(r -> r.fail("앱이 종료되어 중단되었습니다. 같은 설정으로 다시 실행해 주세요."));
        List<ModelRun> tests = repository.findByTestStatus(ModelRun.TestStatus.RUNNING);
        tests.forEach(r -> r.testFailed("앱이 종료되어 중단되었습니다."));
        return runs.size() + tests.size();
    }
}
