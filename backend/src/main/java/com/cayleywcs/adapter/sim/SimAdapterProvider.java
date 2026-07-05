package com.cayleywcs.adapter.sim;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import com.cayleywcs.simulator.DeviceSimFactory;
import com.cayleywcs.simulator.DeviceSimRegistry;
import com.cayleywcs.simulator.StackerSimulatorRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SimAdapterProvider implements ProtocolAdapterProvider {
    private final DeviceSimRegistry registry;
    private final DeviceSimFactory simFactory;

    @Autowired
    public SimAdapterProvider(DeviceSimRegistry registry, DeviceSimFactory simFactory) {
        this.registry = registry;
        this.simFactory = simFactory;
    }

    /** 测试便捷构造：绑定既有堆垛机注册表的内部仓，设备类型由 device_type 决定（缺省=堆垛机）。 */
    public SimAdapterProvider(StackerSimulatorRegistry legacy) {
        this(legacy.store(), new DeviceSimFactory());
    }

    @Override
    public String protocolType() {
        return "sim";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        String deviceType = ctx.application().getDevice_type();
        return new SimAdapter(registry, ctx.application().getId(), simFactory.supplier(deviceType));
    }
}
