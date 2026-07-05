package com.cayleywcs.task.orchestrate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 派工策略注册表。未知/空策略码回落 priority（既有行为）。 */
@Component
public class TaskSelectionStrategyRegistry {
    public static final String DEFAULT = "priority";

    private final Map<String, TaskSelectionStrategy> strategies;

    public TaskSelectionStrategyRegistry(List<TaskSelectionStrategy> strategyList) {
        Map<String, TaskSelectionStrategy> map = new LinkedHashMap<>();
        for (TaskSelectionStrategy s : strategyList) {
            map.putIfAbsent(s.code().toLowerCase(Locale.ROOT), s);
        }
        this.strategies = Map.copyOf(map);
    }

    public TaskSelectionStrategy byCodeOrDefault(String code) {
        if (code != null && !code.isBlank()) {
            TaskSelectionStrategy s = strategies.get(code.toLowerCase(Locale.ROOT));
            if (s != null) {
                return s;
            }
        }
        return strategies.get(DEFAULT);
    }
}
