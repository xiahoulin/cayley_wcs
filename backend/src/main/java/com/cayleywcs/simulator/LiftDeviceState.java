package com.cayleywcs.simulator;

/**
 * 提升机/电梯内存仿真：接受目标层+启动命令，运行若干节拍后到层并开门（status_Task=2, status_DoorState=1），
 * 完成确认后关门归零。点位：cmd_TargetFloor/cmd_TaskNum/cmd_Go（命令），
 * status_Mode/status_Task/status_TaskNum/status_CurrentFloor/status_DoorState/status_ErrorCode（状态）。
 */
public class LiftDeviceState extends AbstractDeviceSim {
    private int taskTicks = 0;

    public LiftDeviceState() {
        seed();
    }

    @Override
    protected void seed() {
        taskTicks = 0;
        store.put("status_Mode", 2);
        store.put("status_Task", 0);
        store.put("status_TaskNum", 0);
        store.put("status_CurrentFloor", 1);
        store.put("status_DoorState", 0);   // 0关/1开
        store.put("status_ErrorCode", 0);
        store.put("PLC_Heart", 0);
    }

    @Override
    protected void onSet(String field, int intValue) {
        if (!"cmd_Go".equals(field)) {
            return;
        }
        int task = intVal("status_Task");
        if (intValue == 1 && task == 0) {
            store.put("status_Task", 1);
            store.put("status_TaskNum", intVal("cmd_TaskNum"));
            store.put("status_DoorState", 0); // 运行中关门
            taskTicks = 0;
        } else if (intValue == 0 && task == 2) {
            store.put("status_Task", 0);
            store.put("status_DoorState", 0); // 离开关门
        }
    }

    @Override
    protected void onTick() {
        if (intVal("status_Task") == 1 && ++taskTicks >= 2) {
            store.put("status_Task", 2);
            store.put("status_CurrentFloor", intVal("cmd_TargetFloor"));
            store.put("status_DoorState", 1); // 到层开门
            taskTicks = 0;
        }
    }
}
