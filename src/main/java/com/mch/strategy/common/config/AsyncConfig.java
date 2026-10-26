package com.mch.strategy.common.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 데이터 받기 같은 오래 걸리는 작업을 백그라운드에서 실행한다.
 * 동시에 하나씩만 돌려서 거래소 호출 제한과 DB 부하를 단순하게 관리한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = com.mch.strategy.common.AsyncConfigNames.JOB_EXECUTOR)
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(500); // 모델 여러 개를 한 번에 고르면 실험이 차례로 쌓인다
        executor.setThreadNamePrefix("job-");
        executor.initialize();
        return executor;
    }
}
