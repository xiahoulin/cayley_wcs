package com.cayleywcs.task;

/** 堆垛机三段握手步（6.18 协议）。 */
public enum HandshakeStep {
    CREATED,            // 新建
    CHECKING,           // 检查阶段：模式=联机自动、无故障、无任务
    PARAMS_WRITTEN,     // 已下发 排/列/层/口/任务号/类型
    EXECUTE_CONFIRMED,  // 已写 cmd_ConfirmTask=1 执行任务
    EXECUTING,          // status_Task=1，已写 cmd_ConfirmTask=0
    COMPLETE_CONFIRMED, // status_Task=2，已写 cmd_ConfirmTask=2 完成确认
    CLEARED,            // 已清零命令区
    DONE,               // 完成
    FAILED              // 失败（故障/超时）
}
