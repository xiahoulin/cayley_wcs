package com.cayleywcs.connection;

import com.cayleywcs.config.WcsMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 连接看门狗：周期检测心跳失活并触发自动重连。
 */
@Component
public class ConnectionWatchdog {
    private final ConnectionManager connectionManager;
    @Autowired private WcsMetrics metrics;

    public ConnectionWatchdog(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Scheduled(fixedDelayString = "${cayleywcs.connection.watchdog-interval-ms:5000}", scheduler = "taskScheduler")
    public void tick() {
        WcsMetrics.tick(metrics, "connection-watchdog", connectionManager::runWatchdogOnce);
    }
}
