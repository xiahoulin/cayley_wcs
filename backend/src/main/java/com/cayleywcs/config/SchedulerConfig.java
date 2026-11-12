package com.cayleywcs.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 核心调度与慢 I/O 调度分池；固定延迟任务仍由各自单个注册保持不重叠。 */
@Configuration
public class SchedulerConfig {
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(@Value("${spring.task.scheduling.pool.size:4}") int poolSize) {
        return scheduler("wcs-core-", Math.max(4, poolSize));
    }

    @Bean(name = "webhookScheduler")
    ThreadPoolTaskScheduler webhookScheduler() {
        return scheduler("wcs-webhook-", 1);
    }

    @Bean(name = "monitorScheduler")
    ThreadPoolTaskScheduler monitorScheduler() {
        return scheduler("wcs-monitor-", 1);
    }

    private static ThreadPoolTaskScheduler scheduler(String prefix, int size) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setAwaitTerminationSeconds(15);
        return scheduler;
    }
}
