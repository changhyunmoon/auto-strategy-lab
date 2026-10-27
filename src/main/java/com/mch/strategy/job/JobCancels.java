package com.mch.strategy.job;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 「중지」 요청을 받은 작업 번호. 백그라운드 작업이 진행 중간중간 확인해서 스스로 멈춘다.
 * 메모리에만 둔다 (앱이 꺼지면 작업도 함께 끝나므로 저장할 필요가 없다).
 */
@Component
public class JobCancels {

    private final Set<Long> requested = ConcurrentHashMap.newKeySet();

    public void request(long jobId) {
        requested.add(jobId);
    }

    public boolean isRequested(long jobId) {
        return requested.contains(jobId);
    }

    /** 중지 요청이 있으면 {@link CancelledException} 을 던진다 */
    public void check(long jobId) {
        if (requested.contains(jobId)) {
            throw new CancelledException();
        }
    }

    public void clear(long jobId) {
        requested.remove(jobId);
    }

    /** 사용자가 중지한 작업 */
    public static class CancelledException extends RuntimeException {
        public CancelledException() {
            super("사용자가 중지했습니다.");
        }
    }
}
