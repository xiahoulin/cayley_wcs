package com.cayleywcs.adapter.sim;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.simulator.StackerDeviceState;
import com.cayleywcs.simulator.StackerSimulatorRegistry;

/**
 * 仿真适配器（protocol_type=sim）：直连内存堆垛机仿真状态，无需硬件即可端到端跑通连接治理/握手/报警。
 */
public class SimAdapter extends AbstractProtocolAdapter {
    private final StackerSimulatorRegistry registry;
    private final Long appId;
    private volatile StackerDeviceState device;

    public SimAdapter(StackerSimulatorRegistry registry, Long appId) {
        this.registry = registry;
        this.appId = appId;
    }

    @Override
    public String protocolType() {
        return "sim";
    }

    @Override
    protected void doConnect(AdapterContext ctx) {
        this.device = registry.getOrCreate(appId);
    }

    @Override
    protected void doDisconnect() {
        this.device = null;
    }

    @Override
    public boolean isConnected() {
        return device != null;
    }

    @Override
    public boolean heartbeat() {
        StackerDeviceState d = device;
        if (d == null) {
            return false;
        }
        d.tick();
        return true;
    }

    @Override
    public Object read(ProtocolPointEntity point) {
        return device.get(point.getField_name());
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        device.set(point.getField_name(), value);
    }
}
