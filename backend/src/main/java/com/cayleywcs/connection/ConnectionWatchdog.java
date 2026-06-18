package com.cayleywcs.connection;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 连接看门狗（需求 4）：周期检测心跳失活并触发自动重连。
 */
@Component
public class ConnectionWatchdog {
    private final ConnectionManager connectionManager;

    public ConnectionWatchdog(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Scheduled(fixedDelayString = "${cayleywcs.connection.watchdog-interval-ms:5000}")
    public void tick() {
        connectionManager.runWatchdogOnce();
    }
}
