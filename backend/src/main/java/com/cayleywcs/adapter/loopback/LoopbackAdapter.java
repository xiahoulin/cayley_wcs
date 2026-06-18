package com.cayleywcs.adapter.loopback;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存回环适配器：不连真实设备，把写入的点位回读出来，并对心跳点位做 0↔1 翻转。
 * 用途：无硬件时验证连接治理与读写链路；也作为协议未实现时的安全占位。
 * conn_params 支持 connectDelayMs（模拟建连耗时，用于演示 60s 超时回收）/ failConnect（模拟建连失败）。
 */
public class LoopbackAdapter extends AbstractProtocolAdapter {
    private final Map<String, Object> store = new ConcurrentHashMap<>();
    private volatile long heartbeatValue = 0;

    @Override
    public String protocolType() {
        return "loopback";
    }

    @Override
    protected void doConnect(AdapterContext ctx) throws Exception {
        long delay = ctx.connParamLong("connectDelayMs", 0);
        if (delay > 0) {
            Thread.sleep(delay);
        }
        if ("true".equalsIgnoreCase(ctx.connParam("failConnect", "false"))) {
            throw new IllegalStateException("loopback simulated connect failure");
        }
    }

    @Override
    protected void doDisconnect() {
        store.clear();
    }

    @Override
    public boolean heartbeat() {
        heartbeatValue = heartbeatValue == 0 ? 1 : 0;
        return isConnected();
    }

    @Override
    public Object read(ProtocolPointEntity point) {
        String field = point.getField_name();
        if (field != null && field.toLowerCase().contains("heart")) {
            return heartbeatValue;
        }
        return store.getOrDefault(field, 0);
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        store.put(point.getField_name(), value);
    }
}
