package com.cayleywcs.task.orchestrate;

import java.util.List;

/**
 * 候选组选机策略 SPI（M10 多对多）：链段声明 candidateAppIds 时，物化前由策略绑定实际设备。
 * 范式对齐 ProtocolAdapterProvider：Spring 注入全部实现，按 wcs_task_chain.select_strategy 路由。
 */
public interface DeviceSelectionStrategy {

    /** 策略码（字典 select_strategy）。 */
    String code();

    /** 从候选中选一台设备；返回 null = 本轮无可绑定设备（编排器等待下轮，超时策略由编排器兜底）。 */
    Long select(List<Long> candidateAppIds, SelectionContext ctx);

    /** 选机上下文：链 id 与段号（无状态策略如 round_robin 以此决定性取模）。 */
    record SelectionContext(Long chainId, Long seq) {
    }
}
