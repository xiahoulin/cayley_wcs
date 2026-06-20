package com.cayleywcs.connection;

import java.util.Map;

/** 连接运行态快照（给前端连接监控页 / 实时看板）。 */
public record ConnectionSnapshot(
        Long appId,
        String appCode,
        String appName,
        String protocolType,
        String state,
        long lastHeartbeatAt,
        int retryCount,
        Map<String, Object> latest
) {
}
