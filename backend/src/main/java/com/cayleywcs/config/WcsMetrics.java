package com.cayleywcs.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 仅允许固定 job/kind 标签；delay 为 fixedDelay 预期启动时刻到实际启动的正向偏差。 */
@Component
public class WcsMetrics {
    private static final Map<String, String> DELAY_PROPERTIES = Map.of(
            "task-engine", "cayleywcs.task.engine-interval-ms",
            "task-recovery", "cayleywcs.task.recovery-interval-ms",
            "chain-orchestrator", "cayleywcs.chain.orchestrate-interval-ms",
            "connection-watchdog", "cayleywcs.connection.watchdog-interval-ms",
            "connection-recovery", "cayleywcs.connection.recovery-reconcile-interval-ms",
            "webhook-scanner", "cayleywcs.webhook.scan-interval-ms",
            "webhook-delivery", "cayleywcs.webhook.deliver-interval-ms",
            "monitor-push", "cayleywcs.monitor.push-interval-ms",
            "webhook-probe", "cayleywcs.metrics.webhook-probe-interval-ms");
    private static final Map<String, Long> DEFAULT_DELAYS = Map.of(
            "task-engine", 1000L,
            "task-recovery", 5000L,
            "chain-orchestrator", 2000L,
            "connection-watchdog", 5000L,
            "connection-recovery", 30000L,
            "webhook-scanner", 3000L,
            "webhook-delivery", 2000L,
            "monitor-push", 1000L,
            "webhook-probe", 60000L);

    private final MeterRegistry registry;
    private final Environment environment;
    private final ConcurrentHashMap<String, Long> lastFinished = new ConcurrentHashMap<>();

    public WcsMetrics(MeterRegistry registry, Environment environment) {
        this.registry = registry;
        this.environment = environment;
    }

    public static void tick(WcsMetrics metrics, String job, Runnable action) {
        if (metrics == null) {
            action.run(); // 独立构造的纯业务单测不需要启动观测组件。
        } else {
            metrics.observeTick(job, action);
        }
    }

    private void observeTick(String job, Runnable action) {
        if (!DELAY_PROPERTIES.containsKey(job) && !"message-log-cleaner".equals(job)) {
            throw new IllegalArgumentException("Unknown scheduler metric label: " + job);
        }
        long started = System.nanoTime();
        Long previous = lastFinished.get(job);
        if (previous != null && DELAY_PROPERTIES.containsKey(job)) {
            long delayMillis = environment.getProperty(DELAY_PROPERTIES.get(job), Long.class,
                    DEFAULT_DELAYS.get(job));
            long lateNanos = Math.max(0, started - previous - TimeUnit.MILLISECONDS.toNanos(delayMillis));
            registry.timer("wcs.scheduler.delay", "job", job).record(lateNanos, TimeUnit.NANOSECONDS);
        }
        try {
            action.run();
        } finally {
            long finished = System.nanoTime();
            registry.timer("wcs.scheduler.duration", "job", job)
                    .record(Math.max(0, finished - started), TimeUnit.NANOSECONDS);
            lastFinished.put(job, finished);
        }
    }

    public void registerTaskWorker(Set<Long> busy) {
        Gauge.builder("wcs.task.worker.active", busy, Set::size).register(registry);
    }

    public long auditFailure(String kind) {
        if (!"connection".equals(kind) && !"message".equals(kind)) {
            throw new IllegalArgumentException("Unknown audit kind: " + kind);
        }
        return increment("wcs.audit.write.failures", "kind", kind);
    }

    public long monitorFailure() {
        return increment("wcs.monitor.push.failures", "kind", "frame");
    }

    private long increment(String name, String label, String value) {
        var counter = registry.counter(name, label, value);
        counter.increment();
        return (long) counter.count();
    }

    public static boolean shouldLogFailure(long count) {
        return count == 1 || (count & (count - 1)) == 0;
    }
}
