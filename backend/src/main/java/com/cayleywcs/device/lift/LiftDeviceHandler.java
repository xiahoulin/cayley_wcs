package com.cayleywcs.device.lift;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.AbstractDeviceHandler;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 提升机/电梯设备 handler（device_type=lift）。
 * 任务 payload：{@code cmd_TargetFloor}（目标层，必填）。
 * 流程：到层且门开后保存结果，持久化清理阶段，再确认设备关门回空闲。
 */
@Component
public class LiftDeviceHandler extends AbstractDeviceHandler {
    private static final int MODE_ONLINE_AUTO = 2;
    private static final int DOOR_OPEN = 1;

    private enum Step { CREATED, CHECKING, MOVING, CLEARING, DONE, FAILED }

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
            case CLEARING -> clearCompleted(t, appId);
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
        Long floor = payloadLongOrNull(t, "cmd_TargetFloor");
        if (floor == null) {
            fail(t, "提升机任务缺少必填点位 cmd_TargetFloor", 0);
            return false;
        }
        write(appId, "cmd_TargetFloor", floor);
        write(appId, "cmd_TaskNum", nativeNumber(t));
        t.setHandshake_step(Step.MOVING.name());
        t.setStatus(TaskStatus.EXECUTING);
        t.setDispatch_time(LocalDateTime.now());
        beginPhysical(t);
        if (!claim(t)) {
            t.setHandshake_step(Step.CHECKING.name());
            return true;
        }
        write(appId, "cmd_Go", 1);
        return true;
    }

    private boolean waitArrive(TaskEntity t, Long appId) {
        if (t.getResult()!=null) return enterClearing(t);
        int st = readInt(appId, "status_Task");
        if (st == 2) {
            if (readInt(appId, "status_DoorState") == DOOR_OPEN) {
                captureResult(t);
                return enterClearing(t);
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

    private boolean enterClearing(TaskEntity t) {
        t.setHandshake_step(Step.CLEARING.name());claim(t);resetStall(t);return true;
    }

    private boolean clearCompleted(TaskEntity t, Long appId) {
        if (t.getResult()==null) { t.setHeld_reason("清理阶段缺少已保存结果，禁止完成并释放设备任务号"); return true; }
        int state=readInt(appId,"status_Task");
        if (state!=0 && state!=2) { t.setHeld_reason("提升机尚未确认完成清理，等待设备空闲"); return true; }
        if (state==2 && readLong(appId,"status_TaskNum")!=nativeNumber(t)) {
            t.setHeld_reason("提升机清理反馈任务号不属于本任务"); return true;
        }
        write(appId,"cmd_Go",0);write(appId,"cmd_TargetFloor",0);write(appId,"cmd_TaskNum",0);
        if (readInt(appId,"status_Task")!=0 || readInt(appId,"status_DoorState")!=0) { t.setHeld_reason("提升机已发送完成确认，等待设备关门并反馈空闲"); return true; }
        t.setHeld_reason("");t.setHandshake_step(Step.DONE.name());t.setStatus(TaskStatus.COMPLETED);
        t.setFinish_time(LocalDateTime.now());resetStall(t);return false;
    }

    private Step parse(String s) {
        try {
            return Step.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return Step.CREATED;
        }
    }
}
