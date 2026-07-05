package com.cayleywcs.device.autodoor;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.AbstractDeviceHandler;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 自动门设备 handler（device_type=auto_door）。
 * 任务 payload：{@code action}=open|close（默认 open）。
 * 流程：检查(联机自动+无故障) → 发开/关命令 → 等门到位。障碍物故障码经故障闸判失败。
 * status_DoorState：0关/1开/2开门中/3关门中。
 */
@Component
public class AutoDoorDeviceHandler extends AbstractDeviceHandler {
    private static final int MODE_ONLINE_AUTO = 2;
    private static final int DOOR_CLOSED = 0;
    private static final int DOOR_OPEN = 1;
    private static final int DOOR_OPENING = 2;
    private static final int DOOR_CLOSING = 3;

    private enum Step { CREATED, COMMANDING, DONE, FAILED }

    public AutoDoorDeviceHandler(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    public String deviceType() {
        return "auto_door";
    }

    @Override
    public boolean advance(TaskEntity task) {
        return advanceGuarded(task, (t, appId, stepName) -> switch (parse(stepName)) {
            case CREATED -> command(t, appId);
            case COMMANDING -> waitDoor(t, appId);
            case DONE, FAILED -> {
                resetStall(t);
                yield false;
            }
        });
    }

    private boolean command(TaskEntity t, Long appId) {
        // 必填/合法性校验：action 必须 open|close，非法即判失败（不默认开门）
        String action = payloadString(t, "action", "");
        if (!"open".equalsIgnoreCase(action) && !"close".equalsIgnoreCase(action)) {
            fail(t, "自动门任务 action 非法(需 open|close): '" + action + "'", 0);
            return false;
        }
        if (readInt(appId, "status_Mode") != MODE_ONLINE_AUTO || connectionManager.hasActiveFault(appId)) {
            return true; // 未就绪，下轮再试
        }
        boolean open = "open".equalsIgnoreCase(action);
        t.setHandshake_step(Step.COMMANDING.name());
        t.setStatus(TaskStatus.EXECUTING);
        t.setDispatch_time(LocalDateTime.now());
        // 门动作是物理动作：占位提交防 cancel 竞态，赢不到则回退不发命令
        if (!claim(t)) {
            t.setHandshake_step(Step.CREATED.name());
            t.setStatus(TaskStatus.PENDING);
            return true;
        }
        write(appId, open ? "cmd_Open" : "cmd_Close", 1);
        return true;
    }

    private boolean waitDoor(TaskEntity t, Long appId) {
        boolean open = "open".equalsIgnoreCase(payloadString(t, "action", ""));
        int target = open ? DOOR_OPEN : DOOR_CLOSED;
        int door = readInt(appId, "status_DoorState");
        if (door == target) {
            write(appId, "cmd_Open", 0);
            write(appId, "cmd_Close", 0);
            t.setHandshake_step(Step.DONE.name());
            t.setStatus(TaskStatus.COMPLETED);
            t.setFinish_time(LocalDateTime.now());
            resetStall(t);
            return false;
        }
        // 门在过渡态(开门中/关门中)=正在运动，视为有进展、不计死等（慢门不被误杀）
        if (door == DOOR_OPENING || door == DOOR_CLOSING) {
            resetStall(t);
            return true;
        }
        // 停在错误终态（命令未生效/被复位）：有界重试后判死等
        if (bumpStall(t) >= STALL_LIMIT) {
            fail(t, "自动门停在非目标态未运动(status_DoorState=" + door + ")", 0);
            return false;
        }
        return true;
    }

    private Step parse(String s) {
        try {
            return Step.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return Step.CREATED;
        }
    }
}
