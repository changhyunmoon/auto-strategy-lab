package com.mch.strategy.job;

public enum JobStatus {
    RUNNING,
    SUCCEEDED,
    /** 실패, 또는 앱이 꺼져서 끝나지 못한 작업. 이어서 처리하지 않고 다시 실행한다. */
    FAILED
}
