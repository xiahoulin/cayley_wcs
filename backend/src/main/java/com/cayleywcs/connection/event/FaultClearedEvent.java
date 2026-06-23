package com.cayleywcs.connection.event;

/**
 * 单个设备故障码恢复事件：轮询发现某故障码从 status_ErrorCode 中消失时发布（支持多故障逐个恢复）。
 * 与 {@link FaultDetectedEvent} code=0 的“全清”互补——本事件按单码精确解除，避免部分恢复残留幽灵报警。
 */
public record FaultClearedEvent(
        Long appId,
        Long protocolId,
        int code
) {
}
