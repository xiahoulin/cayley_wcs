package com.cayleywcs.monitor;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.connection.ConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期向 WebSocket 客户端推送：全部连接快照 + 连接槽 + 活动报警。供前端实时看板/报警中心。
 */
@Component
public class MonitorPusher {
    private static final Logger log = LoggerFactory.getLogger(MonitorPusher.class);

    private final ConnectionManager connectionManager;
    private final AlarmService alarmService;
    private final MonitorWebSocketHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MonitorPusher(ConnectionManager connectionManager, AlarmService alarmService,
                         MonitorWebSocketHandler handler) {
        this.connectionManager = connectionManager;
        this.alarmService = alarmService;
        this.handler = handler;
    }

    @Scheduled(fixedDelayString = "${cayleywcs.monitor.push-interval-ms:1000}")
    public void push() {
        if (handler.sessionCount() == 0) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "type", "monitor",
                    "connections", connectionManager.snapshots(),
                    "slots", connectionManager.slotUsage(),
                    "alarms", alarmService.listActive(null));
            handler.broadcast(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            log.debug("monitor push failed: {}", ex.getMessage());
        }
    }
}
