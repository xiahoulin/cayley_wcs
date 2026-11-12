package com.cayleywcs.monitor;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.config.WcsMetrics;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 周期向 WebSocket 客户端推送：全部连接快照 + 连接槽 + 活动报警。供前端实时看板/报警中心。
 */
@Component
public class MonitorPusher {
    private static final Logger log = LoggerFactory.getLogger(MonitorPusher.class);

    private final ConnectionManager connectionManager;
    private final AlarmService alarmService;
    private final MonitorWebSocketHandler handler;
    private final ObjectMapper objectMapper;
    private final AtomicLong seq = new AtomicLong(0);
    @Autowired private WcsMetrics metrics;

    public MonitorPusher(ConnectionManager connectionManager, AlarmService alarmService,
                         MonitorWebSocketHandler handler, ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.alarmService = alarmService;
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${cayleywcs.monitor.push-interval-ms:1000}", scheduler = "monitorScheduler")
    public void push() {
        WcsMetrics.tick(metrics, "monitor-push", this::pushOnce);
    }

    private void pushOnce() {
        if (handler.sessionCount() == 0) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "type", "monitor",
                    "seq", seq.incrementAndGet(),          // 单调递增；WMS 断号即可触发 REST 对账
                    "ts", System.currentTimeMillis(),
                    "connections", connectionManager.snapshots(),
                    "slots", connectionManager.slotUsage(),
                    "alarms", alarmService.listActive(null));
            handler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            long failures = metrics == null ? 1 : metrics.monitorFailure();
            if (WcsMetrics.shouldLogFailure(failures)) {
                log.warn("monitor push failed (count={}): {}", failures, ex.getMessage());
            }
        }
    }
}
