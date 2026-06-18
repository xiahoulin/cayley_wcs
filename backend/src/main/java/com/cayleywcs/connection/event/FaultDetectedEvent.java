package com.cayleywcs.connection.event;

/**
 * 设备故障码边沿事件：轮询发现 status_ErrorCode 变化时发布。code=0 表示故障恢复。
 */
public record FaultDetectedEvent(
        Long appId,
        Long protocolId,
        int code
) {
}
