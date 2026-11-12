package com.cayleywcs.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟一次数据库快照，避免每次 /metrics 抓取都扫描投递大表。 */
@Component
public class WebhookBacklogMetrics {
    private final JdbcTemplate jdbc;
    private final WcsMetrics metrics;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestWaitSeconds = new AtomicLong();

    public WebhookBacklogMetrics(JdbcTemplate jdbc, MeterRegistry registry, WcsMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        Gauge.builder("wcs.webhook.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("wcs.webhook.oldest.wait.seconds", oldestWaitSeconds, AtomicLong::get)
                .register(registry);
    }

    @Scheduled(initialDelayString = "${cayleywcs.metrics.webhook-probe-interval-ms:60000}",
            fixedDelayString = "${cayleywcs.metrics.webhook-probe-interval-ms:60000}", scheduler = "taskScheduler")
    public void tick() {
        WcsMetrics.tick(metrics, "webhook-probe", this::sample);
    }

    void sample() {
        jdbc.query("select count(*) pending_count, min(\"create_time\") oldest"
                        + " from \"wcs_webhook_delivery\" where \"is_valid\"=true and \"status\"='pending'",
                result -> {
                    pending.set(result.getLong("pending_count"));
                    Timestamp oldest = result.getTimestamp("oldest");
                    oldestWaitSeconds.set(oldest == null ? 0 : Math.max(0,
                            Duration.between(oldest.toLocalDateTime(), LocalDateTime.now()).toSeconds()));
                });
    }
}
