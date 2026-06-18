package com.cayleywcs.simulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 堆垛机设备仿真状态（6.18 协议）。内存模拟一台堆垛机：
 * PLC 心跳翻转、模式=联机自动、接受命令、自动推进取放货三段握手、回报状态/速度/位置、可注入故障码。
 * 线程安全：所有读写经同一实例的 synchronized 方法。
 */
public class StackerDeviceState {
    private final Map<String, Object> store = new ConcurrentHashMap<>();
    private long plcHeart = 0;
    private int taskTicks = 0;

    public StackerDeviceState() {
        store.put("status_Mode", 2);              // 联机自动
        store.put("status_Task", 0);              // 无任务
        store.put("status_TaskTypeFeedback", 0);  // 待机
        store.put("PLC_Heart", 0);
        store.put("status_Cargo", 1);             // 载货台空
        store.put("status_CurrentColumnNum", 1);
        store.put("status_CurrentFloorNum", 1);
        store.put("status_Speed_Lift", 0.0);
        store.put("status_Speed_Walk", 0.0);
        store.put("status_Speed_Fork", 0.0);
        store.put("status_CurrentPos_Walk", 0.0);
        store.put("status_CurrentPos_Lift", 0.0);
        store.put("status_CurrentPos_Fork", 0.0);
        store.put("status_TaskNum", 0);
        store.put("status_ErrorCode", 0);
    }

    /** 心跳节拍：翻转 PLC_Heart 并推进任务执行（执行中→2 拍后完成）。 */
    public synchronized void tick() {
        plcHeart = plcHeart == 0 ? 1 : 0;
        store.put("PLC_Heart", plcHeart);
        if (intVal("status_Task") == 1) {
            taskTicks++;
            store.put("status_Speed_Walk", 1500.0);
            store.put("status_Speed_Lift", 800.0);
            if (taskTicks >= 2) {
                // 执行完成：移动到放货目标，置任务完成
                store.put("status_Task", 2);
                store.put("status_CurrentColumnNum", intVal("cmd_PutCoor_Column"));
                store.put("status_CurrentFloorNum", intVal("cmd_PutCoor_Floor"));
                store.put("status_Speed_Walk", 0.0);
                store.put("status_Speed_Lift", 0.0);
                store.put("status_Cargo", 1);
                taskTicks = 0;
            }
        }
    }

    public synchronized Object get(String field) {
        return store.getOrDefault(field, 0);
    }

    public synchronized void set(String field, Object value) {
        store.put(field, value);
        if ("cmd_ConfirmTask".equals(field)) {
            handleConfirm(toInt(value));
        }
    }

    public synchronized void injectFault(int code) {
        store.put("status_ErrorCode", code);
    }

    public synchronized void clearFault() {
        store.put("status_ErrorCode", 0);
    }

    public synchronized Map<String, Object> snapshot() {
        return Map.copyOf(store);
    }

    private void handleConfirm(int confirm) {
        int task = intVal("status_Task");
        if (confirm == 1 && task == 0) {
            // 开始执行：回读任务类型/号，移动到取货位
            store.put("status_Task", 1);
            store.put("status_TaskTypeFeedback", intVal("cmd_TaskType"));
            store.put("status_TaskNum", intVal("cmd_TaskNum"));
            store.put("status_CurrentColumnNum", intVal("cmd_TakeCoor_Column"));
            store.put("status_CurrentFloorNum", intVal("cmd_TakeCoor_Floor"));
            store.put("status_Cargo", 2);
            taskTicks = 0;
        } else if (confirm == 2) {
            // 完成确认：回到无任务
            store.put("status_Task", 0);
            store.put("status_TaskTypeFeedback", 0);
        }
    }

    private int intVal(String field) {
        return toInt(store.get(field));
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
