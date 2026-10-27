package com.mch.strategy.dataset;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.mch.strategy.collect.CollectLog;
import com.mch.strategy.collect.CollectLogRepository;
import com.mch.strategy.training.ModelRunStateService;
import com.mch.strategy.trainset.TrainingSetStateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 앱이 작업 도중 꺼졌다면 끝나지 못한 원본 받기 · 데이터세트 · 학습 데이터 · 실험을 실패로 표시만 한다 (이어서 처리하지 않는다). */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupCleanup {

    private final DatasetStateService datasetStateService;
    private final TrainingSetStateService trainingSetStateService;
    private final ModelRunStateService modelRunStateService;
    private final CollectLogRepository collectLogRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        int datasets = datasetStateService.failInterrupted();
        int trainingSets = trainingSetStateService.failInterrupted();
        int runs = modelRunStateService.failInterrupted();
        List<CollectLog> collecting = collectLogRepository.findByStatus(CollectLog.RUNNING);
        collecting.forEach(l -> l.fail(l.getRowCount(), "앱이 종료되어 중단되었습니다."));
        collectLogRepository.saveAll(collecting);
        if (datasets + trainingSets + runs + collecting.size() > 0) {
            log.info("이전 실행에서 끝나지 못한 작업을 실패로 표시했습니다: 원본 받기 {} · 데이터세트 {} · 학습 데이터 {} · 실험 {}",
                    collecting.size(), datasets, trainingSets, runs);
        }
    }
}
