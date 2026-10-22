package com.mch.strategy.dataset;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupCleanup {

    private final DatasetStateService datasetStateService;

    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        int count = datasetStateService.failInterrupted();
        if (count > 0) {
            log.info("이전 실행에서 끝나지 못한 데이터세트 {}개를 실패로 표시했습니다.", count);
        }
    }
}
