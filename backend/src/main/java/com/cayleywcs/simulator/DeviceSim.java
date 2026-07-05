package com.cayleywcs.simulator;

import java.util.Map;

/**
 * 内存设备仿真模型 SPI（M8 泛化）：每类设备一个实现（堆垛机/输送线/电梯/自动门…），
 * 供 sim 协议适配器与仿真控制接口读写。实现须线程安全。
 */
public interface DeviceSim {

    /** 心跳节拍：推进设备内部行为（心跳翻转/任务执行推进等）。 */
    void tick();

    Object get(String field);

    void set(String field, Object value);

    /** 注入故障码（0=清除）。 */
    void injectFault(int code);

    void clearFault();

    /** 原地复位到初始状态（不更换实例，保证已连接适配器/OPC UA 命名空间持有的引用继续有效）。 */
    void reset();

    /** 设备内部状态快照（联调可视）。 */
    Map<String, Object> snapshot();
}
