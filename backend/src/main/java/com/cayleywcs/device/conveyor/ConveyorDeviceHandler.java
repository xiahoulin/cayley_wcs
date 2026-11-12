package com.cayleywcs.device.conveyor;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.AbstractDeviceHandler;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 输送线/滚筒线设备 handler（device_type=conveyor）。
 * 任务 payload：{@code cmd_DestStation}（目的站台，必填）。
 * 流程：检查 → 启动 → 到位并保存结果 → 持久化清理阶段 → 清零并确认设备空闲。
 */
@Component
public class ConveyorDeviceHandler extends AbstractDeviceHandler {
    private static final int MODE_ONLINE_AUTO = 2;

    private enum Step { CREATED, CHECKING, MOVING, CLEARING, DONE, FAILED }

    public ConveyorDeviceHandler(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    public String deviceType() {
        return "conveyor";
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
        // 必填参数校验：缺失即判失败，不用 0 号站命令设备（防误运输）
        Long dest = payloadLongOrNull(t, "cmd_DestStation");
        if (dest == null) {
            fail(t, "输送线任务缺少必填点位 cmd_DestStation", 0);
            return false;
        }
        // 参数写入（未启动，可重复无副作用）
        write(appId, "cmd_DestStation", dest);
        write(appId, "cmd_TaskNum", nativeNumber(t));
        t.setHandshake_step(Step.MOVING.name());
        t.setStatus(TaskStatus.EXECUTING);
        t.setDispatch_time(LocalDateTime.now());
        beginPhysical(t);
        // 启动前占位提交（cancel 竞态防护）：并发取消赢了 → 回退，不写启动
        if (!claim(t)) {
            t.setHandshake_step(Step.CHECKING.name());
            return true;
        }
        write(appId, "cmd_Start", 1);
        return true;
    }

    private boolean waitArrive(TaskEntity t, Long appId) {
        if (t.getResult()!=null) return enterClearing(t);
        int st = readInt(appId, "status_Task");
        if (st == 2) {
            captureResult(t);
            return enterClearing(t);
        }
        if (st == 0) {
            // 启动后设备回空闲：疑似设备复位/启动丢失，连续多轮无进展判失败（避免无限空转）
            if (bumpStall(t) >= STALL_LIMIT) {
                fail(t, "输送线启动后任务态回 0，疑似设备复位/启动丢失", 0);
                return false;
            }
        } else {
            resetStall(t);
        }
        return true;
    }

    private boolean enterClearing(TaskEntity t) {
        t.setHandshake_step(Step.CLEARING.name());
        claim(t); // 阶段先落库；无论冲突或成功，本轮均不执行清理I/O。
        resetStall(t);
        return true;
    }

    private boolean clearCompleted(TaskEntity t, Long appId) {
        if (t.getResult()==null) { t.setHeld_reason("清理阶段缺少已保存结果，禁止完成并释放设备任务号"); return true; }
        int state=readInt(appId,"status_Task");
        if (state!=0 && state!=2) { t.setHeld_reason("输送线尚未确认完成清理，等待设备空闲"); return true; }
        if (state==2 && readLong(appId,"status_TaskNum")!=nativeNumber(t)) {
            t.setHeld_reason("输送线清理反馈任务号不属于本任务"); return true;
        }
        write(appId,"cmd_Start",0);
        write(appId,"cmd_DestStation",0);
        write(appId,"cmd_TaskNum",0);
        if (readInt(appId,"status_Task")!=0) { t.setHeld_reason("输送线已发送完成确认，等待设备反馈空闲"); return true; }
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
