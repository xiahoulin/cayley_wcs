package com.cayleywcs.simulator;

import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 仿真设备工厂（M9）：按 device_type 给出对应内存仿真的构造器。
 * 无 Spring 依赖，可直接 new（供 sim 适配器/仿真控制接口/测试复用）。未知或空类型默认堆垛机（向后兼容既有 sim 应用）。
 */
@Component
public class DeviceSimFactory {

    public Supplier<? extends DeviceSim> supplier(String deviceType) {
        String type = deviceType == null ? "" : deviceType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "conveyor" -> ConveyorDeviceState::new;
            case "lift" -> LiftDeviceState::new;
            case "auto_door" -> AutoDoorDeviceState::new;
            default -> StackerDeviceState::new; // stacker/palletizer/空/未知
        };
    }
}
