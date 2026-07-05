package com.cayleywcs.task.orchestrate;

import com.cayleywcs.task.entity.TaskEntity;

/**
 * 派工策略 SPI（M10）：只决定 pending 任务的排序——「在飞优先」是设备级单飞下的正确性约束，
 * 固化在 findNextForApp 前置，不属可配置策略。按 wcs_application.dispatch_strategy 路由。
 */
public interface TaskSelectionStrategy {

    /** 策略码（字典 dispatch_strategy）。 */
    String code();

    /** 选该应用下一个待派 pending 任务；null=无。 */
    TaskEntity nextPending(Long appId);
}
