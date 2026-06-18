package com.cayleywcs.connection;

import com.fasterxml.jackson.databind.JsonNode;

/** 连接运行态快照（给前端连接监控页 / 实时看板）。 */
public record ConnectionSnapshot(
        Long appId,
        String appCode,
        String appName,
        String protocolType,
        String state,
        long lastHeartbeatAt,
        int retryCount,
        JsonNode latest
) {
}
