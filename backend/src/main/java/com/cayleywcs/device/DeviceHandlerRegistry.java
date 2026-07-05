package com.cayleywcs.device;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 设备 handler 注册表（复制 ProtocolAdapterFactory 模式）：Spring 注入全部 {@link DeviceHandler}，
 * 按 device_type 路由。未知类型不抛异常——由调用方决定处置（应用保存时拒绝、引擎运行期报警）。
 */
@Component
public class DeviceHandlerRegistry {
    private final Map<String, DeviceHandler> handlers;

    public DeviceHandlerRegistry(List<DeviceHandler> handlerList) {
        Map<String, DeviceHandler> map = new LinkedHashMap<>();
        for (DeviceHandler handler : handlerList) {
            for (String type : handler.deviceTypes()) {
                map.putIfAbsent(type.toLowerCase(Locale.ROOT), handler);
            }
        }
        this.handlers = Map.copyOf(map);
    }

    public Optional<DeviceHandler> find(String deviceType) {
        if (deviceType == null || deviceType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(deviceType.toLowerCase(Locale.ROOT)));
    }

    public boolean supports(String deviceType) {
        return find(deviceType).isPresent();
    }

    public Set<String> supportedTypes() {
        return handlers.keySet();
    }
}
