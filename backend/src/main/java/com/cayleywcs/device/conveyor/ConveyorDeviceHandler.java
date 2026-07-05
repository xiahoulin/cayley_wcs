package com.cayleywcs.device.conveyor;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.AbstractDeviceHandler;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 输送线/滚筒线设备 handler（device_type=conveyor）。
 * 任务 payload：{@code toStation}（目的站台，必填），{@code fromStation}（来源站台，记录用）。
 * 流程：检查(联机自动+空闲+无故障) → 写目的站+任务号 → 启动 → 等到位 → 清零完成。
 */
@Component
public class ConveyorDeviceHandler extends AbstractDeviceHandler {
    private static final int MODE_ONLINE_AUTO = 2;

    private enum Step { CREATED, CHECKING, MOVING, DONE, FAILED }

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
        Long dest = payloadLongOrNull(t, "toStation");
        if (dest == null) {
            fail(t, "输送线任务缺少必填参数 toStation", 0);
            return false;
        }
        // 参数写入（未启动，可重复无副作用）
        write(appId, "cmd_DestStation", dest);
        write(appId, "cmd_TaskNum", taskNum(t));
        t.setHandshake_step(Step.MOVING.name());
        t.setStatus(TaskStatus.EXECUTING);
        t.setDispatch_time(LocalDateTime.now());
        // 启动前占位提交（cancel 竞态防护）：并发取消赢了 → 回退，不写启动
        if (!claim(t)) {
            t.setHandshake_step(Step.CHECKING.name());
            return true;
        }
        write(appId, "cmd_Start", 1);
        return true;
    }

    private boolean waitArrive(TaskEntity t, Long appId) {
        int st = readInt(appId, "status_Task");
        if (st == 2) {
            write(appId, "cmd_Start", 0);      // 完成确认/清零
            write(appId, "cmd_DestStation", 0);
            write(appId, "cmd_TaskNum", 0);
            t.setHandshake_step(Step.DONE.name());
            t.setStatus(TaskStatus.COMPLETED);
            t.setFinish_time(LocalDateTime.now());
            resetStall(t);
            return false;
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

    private Step parse(String s) {
        try {
            return Step.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return Step.CREATED;
        }
    }
}
