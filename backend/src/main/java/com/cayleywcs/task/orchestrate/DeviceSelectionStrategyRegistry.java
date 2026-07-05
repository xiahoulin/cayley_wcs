package com.cayleywcs.task.orchestrate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 选机策略注册表（复制 ProtocolAdapterFactory 模式）。未知/空策略码回落 idle_first。 */
@Component
public class DeviceSelectionStrategyRegistry {
    public static final String DEFAULT = "idle_first";

    private final Map<String, DeviceSelectionStrategy> strategies;

    public DeviceSelectionStrategyRegistry(List<DeviceSelectionStrategy> strategyList) {
        Map<String, DeviceSelectionStrategy> map = new LinkedHashMap<>();
        for (DeviceSelectionStrategy s : strategyList) {
            map.putIfAbsent(s.code().toLowerCase(Locale.ROOT), s);
        }
        this.strategies = Map.copyOf(map);
    }

    public DeviceSelectionStrategy byCodeOrDefault(String code) {
        if (code != null && !code.isBlank()) {
            DeviceSelectionStrategy s = strategies.get(code.toLowerCase(Locale.ROOT));
            if (s != null) {
                return s;
            }
        }
        return strategies.get(DEFAULT);
    }

    public boolean supports(String code) {
        return code != null && strategies.containsKey(code.toLowerCase(Locale.ROOT));
    }
}
