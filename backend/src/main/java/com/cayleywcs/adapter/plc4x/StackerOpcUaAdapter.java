package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.adapter.AdapterContext;

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

    @Override protected void doConnect(AdapterContext ctx) throws Exception {
        heartbeatPoint = null;
        lastToggleAt = 0;
        wcsHeart = 0;
        super.doConnect(ctx);
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

    /** 从请求方向的内部点位定位 WCS 心跳；没有该驱动点位时仅检查连接存活。 */
    private ProtocolPointEntity resolveHeartbeatPoint() {
        ProtocolPointEntity cached = heartbeatPoint;
        if (cached != null || context == null) {
            return cached;
        }
        heartbeatPoint = context.points().stream()
                .filter(p -> "request".equals(p.getDirection()) && "internal".equals(p.getPoint_scope()))
                .filter(p -> p.getField_name() != null
                        && p.getField_name().toLowerCase().contains("wcs_heart"))
                .findFirst()
                .orElse(null);
        return heartbeatPoint;
    }
}
