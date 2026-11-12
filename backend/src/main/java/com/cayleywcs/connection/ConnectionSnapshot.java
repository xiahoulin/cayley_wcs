package com.cayleywcs.connection;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 连接运行态快照（给前端连接监控页 / 实时看板 / 任务引擎路由）。 */
public record ConnectionSnapshot(
        Long appId,
        String appCode,
        String appName,
        String deviceType,
        String protocolType,
        String state,
        long lastHeartbeatAt,
        int retryCount,
        @JsonProperty("payload") Map<String, Object> latest
) {
    @JsonProperty("quality") public String quality() { return "RUNNING".equals(state) && latest!=null && !latest.isEmpty()?"good":"bad"; }
    @JsonProperty("capturedAt") public long capturedAt() { return lastHeartbeatAt; }
}
