package com.cayleywcs.simulator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 通用仿真设备注册表（M8 泛化）：按 appId 维护任意类型的内存设备仿真，
 * 供 sim 适配器与仿真控制接口共享同一实例。设备类型由调用方的 factory 决定（M9 起按 device_type 建模）。
 */
@Component
public class DeviceSimRegistry {
    private final Map<Long, DeviceSim> devices = new ConcurrentHashMap<>();

    public DeviceSim getOrCreate(Long appId, Supplier<? extends DeviceSim> factory) {
        return devices.computeIfAbsent(appId, k -> factory.get());
    }

    public Optional<DeviceSim> find(Long appId) {
        return Optional.ofNullable(devices.get(appId));
    }

    /**
     * 原地复位（不更换实例）：保证已连接 SimAdapter / OPC UA 命名空间持有的引用继续指向被复位的同一设备，
     * 避免「replace 换新实例 → 旧引用成孤儿 → 注故障/快照落新实例」的分脑。设备不存在则无操作。
     */
    public void reset(Long appId) {
        DeviceSim device = devices.get(appId);
        if (device != null) {
            device.reset();
        }
    }
}
