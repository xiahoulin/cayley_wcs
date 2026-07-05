package com.cayleywcs.device.lift;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.AbstractDeviceHandler;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 提升机/电梯设备 handler（device_type=lift）。
 * 任务 payload：{@code targetFloor}（目标层，必填）。
 * 流程：检查(联机自动+空闲+无故障) → 写目标层+任务号 → 启动 → 等到层且门开 → 清零完成。含门联锁：完成需 status_DoorState=1。
 */
@Component
public class LiftDeviceHandler extends AbstractDeviceHandler {
    private static final int MODE_ONLINE_AUTO = 2;
    private static final int DOOR_OPEN = 1;

    private enum Step { CREATED, CHECKING, MOVING, DONE, FAILED }

    public LiftDeviceHandler(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    public String deviceType() {
        return "lift";
    }

    @Override
    public boolean advance(TaskEntity task) {
        return advanceGuarded(task, (t, appId, stepName) -> switch (parse(stepName)) {
            case CREATED -> check(t, appId);
            case CHECKING -> startMove(t, appId);
            case MOVING -> waitArrive(t, appId);
            case DONE, FAILED -> {
                resetStall(t);
                yield false;
            }
        });
    }

    private boolean check(TaskEntity t, Long appId) {
        if (readInt(appId, "status_Mode") == MODE_ONLINE_AUTO
                && readInt(appId, "status_Task") == 0
                && !connectionManager.hasActiveFault(appId)) {
            t.setHandshake_step(Step.CHECKING.name());
        }
        return true;
    }

    private boolean startMove(TaskEntity t, Long appId) {
        Long floor = payloadLongOrNull(t, "targetFloor");
        if (floor == null) {
            fail(t, "电梯任务缺少必填参数 targetFloor", 0);
            return false;
        }
        write(appId, "cmd_TargetFloor", floor);
        write(appId, "cmd_TaskNum", taskNum(t));
        t.setHandshake_step(Step.MOVING.name());
        t.setStatus(TaskStatus.EXECUTING);
        t.setDispatch_time(LocalDateTime.now());
        if (!claim(t)) {
            t.setHandshake_step(Step.CHECKING.name());
            return true;
        }
        write(appId, "cmd_Go", 1);
        return true;
    }

    private boolean waitArrive(TaskEntity t, Long appId) {
        int st = readInt(appId, "status_Task");
        if (st == 2) {
            if (readInt(appId, "status_DoorState") == DOOR_OPEN) {
                write(appId, "cmd_Go", 0);          // 完成确认（设备关门离开）
                write(appId, "cmd_TargetFloor", 0);
                write(appId, "cmd_TaskNum", 0);
                t.setHandshake_step(Step.DONE.name());
                t.setStatus(TaskStatus.COMPLETED);
                t.setFinish_time(LocalDateTime.now());
                resetStall(t);
                return false;
            }
            // 到层但门迟迟不开（无故障码）：有界等待，避免无限空转饿死队列
            if (bumpStall(t) >= STALL_LIMIT * 2) {
                fail(t, "电梯到层但门未开(status_DoorState≠1)", 0);
                return false;
            }
            return true;
        }
        if (st == 0) {
            if (bumpStall(t) >= STALL_LIMIT) {
                fail(t, "电梯启动后任务态回 0，疑似设备复位/启动丢失", 0);
                return false;
            }
        } else {
            resetStall(t); // st==1 运行中，正常推进
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
