package com.cayleywcs.connection;

/** 连接状态机（需求 4）。 */
public enum ConnectionState {
    NEW,
    CONNECTING,
    CONNECTED,
    RUNNING,
    DISCONNECTED,
    RECONNECTING,
    FAILED,
    CLOSED
}
