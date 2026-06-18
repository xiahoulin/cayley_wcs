package com.cayleywcs.simulator;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 仿真设备注册表：按 appId 维护堆垛机仿真状态，供 sim 适配器与仿真控制接口共享同一实例。
 */
@Component
public class StackerSimulatorRegistry {
    private final Map<Long, StackerDeviceState> devices = new ConcurrentHashMap<>();

    public StackerDeviceState getOrCreate(Long appId) {
        return devices.computeIfAbsent(appId, k -> new StackerDeviceState());
    }

    public Optional<StackerDeviceState> find(Long appId) {
        return Optional.ofNullable(devices.get(appId));
    }

    public void reset(Long appId) {
        devices.put(appId, new StackerDeviceState());
    }
}
