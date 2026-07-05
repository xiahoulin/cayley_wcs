package com.cayleywcs.adapter.sim;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.simulator.DeviceSim;
import com.cayleywcs.simulator.DeviceSimRegistry;
import java.util.function.Supplier;

/**
 * 仿真适配器（protocol_type=sim）：直连内存设备仿真状态，无需硬件即可端到端跑通连接治理/握手/报警。
 * M9 泛化：设备类型由 {@code factory}（按 device_type 选择）决定，堆垛机/输送线/电梯/自动门共用本适配器。
 */
public class SimAdapter extends AbstractProtocolAdapter {
    private final DeviceSimRegistry registry;
    private final Long appId;
    private final Supplier<? extends DeviceSim> factory;
    private volatile DeviceSim device;

    public SimAdapter(DeviceSimRegistry registry, Long appId, Supplier<? extends DeviceSim> factory) {
        this.registry = registry;
        this.appId = appId;
        this.factory = factory;
    }

    @Override
    public String protocolType() {
        return "sim";
    }

    @Override
    protected void doConnect(AdapterContext ctx) {
        this.device = registry.getOrCreate(appId, factory);
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
        DeviceSim d = device;
        if (d == null) {
            return false;
        }
        d.tick();
        return true;
    }

    @Override
    public Object read(ProtocolPointEntity point) {
        DeviceSim d = device;
        if (d == null) {
            throw new IllegalStateException("sim adapter not connected: appId=" + appId);
        }
        return d.get(point.getField_name());
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        DeviceSim d = device;
        if (d == null) {
            throw new IllegalStateException("sim adapter not connected: appId=" + appId);
        }
        d.set(point.getField_name(), value);
    }
}
