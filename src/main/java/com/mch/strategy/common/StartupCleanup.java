package com.mch.strategy.common;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 앱이 작업 도중 꺼졌다면 끝나지 못한 작업을 실패로 표시만 한다 (이어서 처리하지 않는다).
 * 다시 만들려면 상세 화면의 「같은 설정으로 새로 만들기」를 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupCleanup {

    private static final String MSG = "앱이 꺼져서 끝나지 못했습니다. 같은 설정으로 새로 만드세요.";

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        int n = 0;
        for (String table : new String[]{"v2_raw_dataset", "v2_feature_set", "v2_label_set", "v2_split_plan"}) {
            n += jdbc.update("UPDATE " + table + " SET status = 'FAILED', message = ?, finished_at = CURRENT_TIMESTAMP(3)"
                    + " WHERE status IN ('PENDING', 'BUILDING')", MSG);
        }
        jdbc.update("UPDATE job SET status = 'FAILED', message = ?, finished_at = CURRENT_TIMESTAMP(3) WHERE status = 'RUNNING'", MSG);
        if (n > 0) {
            log.info("이전 실행에서 끝나지 못한 V2 작업 {}개를 실패로 표시했습니다.", n);
        }
    }
}
