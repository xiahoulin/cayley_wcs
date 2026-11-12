package com.cayleywcs.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cayleywcs.audit.MessageLogCleaner;
import com.cayleywcs.connection.ConnectionRecoveryRunner;
import com.cayleywcs.connection.ConnectionWatchdog;
import com.cayleywcs.monitor.MonitorPusher;
import com.cayleywcs.task.TaskEngine;
import com.cayleywcs.task.TaskRecoveryScheduler;
import com.cayleywcs.task.orchestrate.TaskOrchestrator;
import com.cayleywcs.webhook.WebhookDeliveryWorker;
import com.cayleywcs.webhook.WebhookScanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test-flyway")
class SchedulerIsolationTest {
    @Autowired @Qualifier("taskScheduler") private ThreadPoolTaskScheduler core;
    @Autowired @Qualifier("webhookScheduler") private ThreadPoolTaskScheduler webhook;
    @Autowired @Qualifier("monitorScheduler") private ThreadPoolTaskScheduler monitor;

    @Test
    void blockingWebhookPoolDoesNotStarveCoreOrMonitor() throws Exception {
        assertThat(core.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
        assertThat(webhook.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        assertThat(monitor.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        assertSchedule(TaskEngine.class, "tick", "taskScheduler");
        assertSchedule(TaskRecoveryScheduler.class, "tick", "taskScheduler");
        assertSchedule(TaskOrchestrator.class, "tick", "taskScheduler");
        assertSchedule(ConnectionWatchdog.class, "tick", "taskScheduler");
        assertSchedule(ConnectionRecoveryRunner.class, "reconcile", "taskScheduler");
        assertSchedule(WebhookScanner.class, "tick", "taskScheduler");
        assertSchedule(MessageLogCleaner.class, "tick", "taskScheduler");
        assertSchedule(WebhookDeliveryWorker.class, "tick", "webhookScheduler");
        assertSchedule(MonitorPusher.class, "push", "monitorScheduler");
        assertSchedule(WebhookBacklogMetrics.class, "tick", "taskScheduler");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch coreRan = new CountDownLatch(1);
        CountDownLatch monitorRan = new CountDownLatch(1);
        webhook.execute(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            core.execute(coreRan::countDown);
            monitor.execute(monitorRan::countDown);
            assertThat(coreRan.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(monitorRan.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    private static void assertSchedule(Class<?> owner, String method, String scheduler) throws Exception {
        Scheduled annotation = owner.getDeclaredMethod(method).getAnnotation(Scheduled.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.scheduler()).isEqualTo(scheduler);
    }
}
