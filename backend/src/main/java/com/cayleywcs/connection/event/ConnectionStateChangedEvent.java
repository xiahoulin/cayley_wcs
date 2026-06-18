package com.cayleywcs.connection.event;

import com.cayleywcs.connection.ConnectionState;

/**
 * 连接状态变化事件（Observer）。报警与 WebSocket 推送在 M5 订阅处理。
 */
public record ConnectionStateChangedEvent(
        Long appId,
        String appCode,
        ConnectionState from,
        ConnectionState to,
        String detail
) {
}
