package com.cayleywcs.simulator;

/**
 * 输送线/滚筒线内存仿真：接受目的站台+启动命令，运输若干节拍后到位（status_Task=2），完成确认后归零。
 * 点位：cmd_DestStation/cmd_TaskNum/cmd_Start（命令），status_Mode/status_Task/status_TaskNum/status_Position/status_Occupied/status_ErrorCode（状态）。
 */
public class ConveyorDeviceState extends AbstractDeviceSim {
    private int taskTicks = 0;

    public ConveyorDeviceState() {
        seed();
    }

    @Override
    protected void seed() {
        taskTicks = 0;
        store.put("status_Mode", 2);        // 联机自动
        store.put("status_Task", 0);        // 0空闲/1运行/2到位
        store.put("status_TaskNum", 0);
        store.put("status_Position", 0);    // 当前站台
        store.put("status_Occupied", 0);    // 0空/1有料
        store.put("status_ErrorCode", 0);
        store.put("PLC_Heart", 0);
    }

    @Override
    protected void onSet(String field, int intValue) {
        if (!"cmd_Start".equals(field)) {
            return;
        }
        int task = intVal("status_Task");
        if (intValue == 1 && task == 0) {
            // 启动：开始向目的站台运输，回读任务号
            store.put("status_Task", 1);
            store.put("status_TaskNum", intVal("cmd_TaskNum"));
            taskTicks = 0;
        } else if (intValue == 0 && task == 2) {
            // 完成确认/清零：回到空闲
            store.put("status_Task", 0);
        }
    }

    @Override
    protected void onTick() {
        if (intVal("status_Task") == 1 && ++taskTicks >= 2) {
            store.put("status_Task", 2);
            store.put("status_Position", intVal("cmd_DestStation"));
            store.put("status_Occupied", 1);
            taskTicks = 0;
        }
    }
}
