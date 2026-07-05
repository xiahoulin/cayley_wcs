package com.cayleywcs.simulator;

/**
 * 自动门内存仿真：接受开/关命令，经一节拍过渡到位。
 * status_DoorState：0关/1开/2开门中/3关门中。点位：cmd_Open/cmd_Close（命令），status_Mode/status_DoorState/status_ErrorCode（状态）。
 * 可注入障碍物故障码（status_ErrorCode）阻断到位。
 */
public class AutoDoorDeviceState extends AbstractDeviceSim {
    private int moveTicks = 0;

    public AutoDoorDeviceState() {
        seed();
    }

    @Override
    protected void seed() {
        moveTicks = 0;
        store.put("status_Mode", 2);
        store.put("status_DoorState", 0);
        store.put("status_ErrorCode", 0);
        store.put("PLC_Heart", 0);
    }

    @Override
    protected void onSet(String field, int intValue) {
        int door = intVal("status_DoorState");
        if ("cmd_Open".equals(field) && intValue == 1 && door != 1) {
            store.put("status_DoorState", 2); // 开门中
            moveTicks = 0;
        } else if ("cmd_Close".equals(field) && intValue == 1 && door != 0) {
            store.put("status_DoorState", 3); // 关门中
            moveTicks = 0;
        }
    }

    @Override
    protected void onTick() {
        int door = intVal("status_DoorState");
        if (door == 2 && ++moveTicks >= 1) {
            store.put("status_DoorState", 1); // 开到位
            moveTicks = 0;
        } else if (door == 3 && ++moveTicks >= 1) {
            store.put("status_DoorState", 0); // 关到位
            moveTicks = 0;
        }
    }
}
