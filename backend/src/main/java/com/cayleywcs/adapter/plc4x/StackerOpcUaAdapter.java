package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;

/**
 * 礁盘工业码垛机（堆垛机）OPC UA 适配器。
 * 基于 PLC4X OPC UA 读写西门子 DB100 映射节点（点位 address 为 OPC UA NodeId）。
 * 在通用 OPC UA 读写之上，实现本协议特有的「主动心跳」：WCS 每 5 秒翻转 WCS_Heart(0↔1) 写入 PLC，
 * 供 PLC 侧判断通讯是否在线（电平心跳，非脉冲）。三段握手由任务引擎 StackerHandshakeStateMachine 驱动。
 */
public class StackerOpcUaAdapter extends Plc4xAdapter {
    /** 协议要求 WCS_Heart「0↔1，5 秒交替」。 */
    private static final long HEARTBEAT_TOGGLE_MS = 5000;

    private volatile int wcsHeart = 0;
    private volatile long lastToggleAt = 0L;
    private volatile ProtocolPointEntity heartbeatPoint;

    public StackerOpcUaAdapter(String connectionString, long timeoutMs) {
        super("opcua", connectionString, timeoutMs);
    }

    @Override
    public boolean heartbeat() {
        if (!isConnected()) {
            return false;
        }
        ProtocolPointEntity hb = resolveHeartbeatPoint();
        if (hb != null) {
            long now = System.currentTimeMillis();
            if (now - lastToggleAt >= HEARTBEAT_TOGGLE_MS) {
                wcsHeart = wcsHeart == 0 ? 1 : 0;
                try {
                    write(hb, wcsHeart);     // 主动写 WCS_Heart 到 PLC
                    lastToggleAt = now;
                } catch (RuntimeException ex) {
                    log.debug("[opcua] write WCS_Heart failed: {}", ex.getMessage());
                    return false;
                }
            }
        }
        return isConnected();
    }

    /** 从点位表定位可写的 WCS 心跳点（field_name 含 wcs_heart 且 rw 含 W）。无则退化为被动心跳。 */
    private ProtocolPointEntity resolveHeartbeatPoint() {
        ProtocolPointEntity cached = heartbeatPoint;
        if (cached != null || context == null) {
            return cached;
        }
        heartbeatPoint = context.points().stream()
                .filter(p -> p.getRw() != null && p.getRw().toUpperCase().contains("W"))
                .filter(p -> p.getField_name() != null
                        && p.getField_name().toLowerCase().contains("wcs_heart"))
                .findFirst()
                .orElse(null);
        return heartbeatPoint;
    }
}
