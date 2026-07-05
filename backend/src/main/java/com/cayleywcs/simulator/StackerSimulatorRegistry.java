package com.cayleywcs.simulator;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 堆垛机仿真注册表：{@link DeviceSimRegistry} 的堆垛机专用门面（保持既有 API，供 sim 适配器/仿真接口/测试使用）。
 * M9 起新设备类型直接走 DeviceSimRegistry 按 device_type 建模。
 */
@Component
public class StackerSimulatorRegistry {
    private final DeviceSimRegistry store;

    @Autowired
    public StackerSimulatorRegistry(DeviceSimRegistry store) {
        this.store = store;
    }

    /** 测试便捷构造：独立内存仓（Spring 注入走上面的共享仓构造）。 */
    public StackerSimulatorRegistry() {
        this(new DeviceSimRegistry());
    }

    public StackerDeviceState getOrCreate(Long appId) {
        return (StackerDeviceState) store.getOrCreate(appId, StackerDeviceState::new);
    }

    public Optional<StackerDeviceState> find(Long appId) {
        return store.find(appId)
                .filter(StackerDeviceState.class::isInstance)
                .map(StackerDeviceState.class::cast);
    }

    public void reset(Long appId) {
        store.getOrCreate(appId, StackerDeviceState::new).reset();
    }

    /** 暴露底层通用仿真仓，供 sim 适配器共享同一实例（保证 find()/adapter 读写同一状态）。 */
    public DeviceSimRegistry store() {
        return store;
    }
}
