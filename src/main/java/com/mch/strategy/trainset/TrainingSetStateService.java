package com.mch.strategy.trainset;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 백그라운드 작업이 학습 데이터 상태를 바꿀 때 쓴다. 호출마다 바로 커밋된다. */
@Service
@RequiredArgsConstructor
public class TrainingSetStateService {

    private final TrainingSetRepository repository;

    @Transactional
    public void building(long id) {
        repository.findById(id).ifPresent(TrainingSet::building);
    }

    @Transactional
    public void ready(long id, long rowCount, List<String> columns, Map<String, Object> stats) {
        repository.findById(id).ifPresent(t -> t.ready(rowCount, columns, stats));
    }

    @Transactional
    public void fail(long id, String message) {
        repository.findById(id).ifPresent(t -> t.fail(message));
    }

    /** 앱이 만드는 도중 꺼졌다면 실패로 표시만 한다 (이어서 만들지 않는다). */
    @Transactional
    public int failInterrupted() {
        List<TrainingSet> list = repository.findByStatusIn(Set.of(TrainingSetStatus.PENDING, TrainingSetStatus.BUILDING));
        list.forEach(t -> t.fail("앱이 종료되어 중단되었습니다. 다시 만들어 주세요."));
        return list.size();
    }
}
