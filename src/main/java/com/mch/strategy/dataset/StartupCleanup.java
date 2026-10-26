package com.mch.strategy.dataset;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.mch.strategy.training.ModelRunStateService;
import com.mch.strategy.trainset.TrainingSetStateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 앱이 작업 도중 꺼졌다면 끝나지 못한 데이터세트 · 학습 데이터 · 실험을 실패로 표시만 한다 (이어서 처리하지 않는다). */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupCleanup {

    private final DatasetStateService datasetStateService;
    private final TrainingSetStateService trainingSetStateService;
    private final ModelRunStateService modelRunStateService;

    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        int datasets = datasetStateService.failInterrupted();
        int trainingSets = trainingSetStateService.failInterrupted();
        int runs = modelRunStateService.failInterrupted();
        if (datasets + trainingSets + runs > 0) {
            log.info("이전 실행에서 끝나지 못한 작업을 실패로 표시했습니다: 데이터세트 {} · 학습 데이터 {} · 실험 {}",
                    datasets, trainingSets, runs);
        }
    }
}
