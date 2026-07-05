package com.cayleywcs.task;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.AbstractDeviceHandler;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 堆垛机三段握手状态机（6.18 协议），设备类型 stacker/palletizer 的 {@link com.cayleywcs.device.DeviceHandler}。
 * 每次 {@link #advance} 推进一步，通过 {@link ConnectionManager} 读写点位。
 * 检查阶段(模式=联机自动/无故障/无任务) → 下发参数 → 执行确认 → 等待完成 → 完成确认 → 清零。
 * 执行期间读到 status_ErrorCode!=0 即判失败（报警在 M5 联动）。
 *
 * 并发/重入契约（M8 对抗审查加固）：
 * - 执行确认（不可回滚的设备写）前先 {@code claim} 占位提交，管理端并发取消赢了则本步废弃不写设备；
 * - 传输/生命周期异常（连接拆除、读写超时、停机中断）≠ 设备故障：本步放弃下轮重试，不落终态；
 * - 设备中途复位（等待完成期间 status_Task 回 0 无故障码）连续多轮无进展 → 判失败，避免无限空转饿死队列。
 */
@Component
public class StackerHandshakeStateMachine extends AbstractDeviceHandler {
    private static final int MODE_ONLINE_AUTO = 2;
    /** 等待完成期间连续读到 status_Task==0 判「设备复位/确认丢失」的轮数阈值。 */
    private static final int RESET_SPIN_ROUNDS = 3;

    /** 等待完成期间 status_Task==0 的连续轮数（内存态：重启清零后重新累计，仍能终止）。 */
    private final Map<String, Integer> resetSpin = new ConcurrentHashMap<>();

    public StackerHandshakeStateMachine(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    public String deviceType() {
        return "stacker";
    }

    @Override
    public List<String> deviceTypes() {
        return List.of("stacker", "palletizer"); // 码垛机走同一 6.18 握手语义（V4 协议克隆自堆垛机）
    }

    /** 推进一步。返回 true 表示任务仍在进行；false 表示已到终态(DONE/FAILED)。 */
    @Override
    public boolean advance(TaskEntity task) {
        Long appId = task.getApp_id();
        HandshakeStep step = currentStep(task);
        try {
            // 故障检查：支持标量(仿真)和数组(真实PLC ARRAY[1..60])。任一非零即判故障。
            int firstErrorCode = readFirstFaultCode(appId);
            if (firstErrorCode != 0 && step != HandshakeStep.DONE && step != HandshakeStep.FAILED) {
                failAndClear(task, "设备故障，故障码=" + firstErrorCode, firstErrorCode);
                return false;
            }
            return switch (step) {
                case CREATED -> doCheck(task, appId);
                case CHECKING -> doWriteParams(task, appId);
                case PARAMS_WRITTEN -> doConfirmExecute(task, appId);
                case EXECUTE_CONFIRMED, EXECUTING -> doWaitComplete(task, appId);
                case COMPLETE_CONFIRMED -> doConfirmComplete(task, appId);
                case CLEARED -> doClear(task, appId);
                case DONE, FAILED -> {
                    clearResetSpin(task);
                    yield false;
                }
            };
        } catch (RuntimeException ex) {
            // 传输/生命周期异常（连接不存在、读写超时、停机中断等）不是设备故障：
            // 本步放弃、任务保持在飞，下轮以设备实况续推（PARAMS_WRITTEN 的重入分支可无损接续）。
            // 只有故障码路径与任务号归属不符才判 FAILED。持续异常由连接看门狗/报警可见，任务级超时在 M11。
            log.warn("task {} handshake {} 步异常，本步放弃下轮重试: {}", task.getTask_no(), step, ex.getMessage());
            return true;
        }
    }

    private boolean doCheck(TaskEntity task, Long appId) {
        int st = readInt(appId, "status_Task");
        if (st != 0) {
            // 再收养（M11 审查 P1）：设备持有的正是本任务号——stale 误判 failed 后重派/崩溃后重派同号任务时，
            // 设备可能已自主完成并停在 status_Task=2 等确认（6.18 协议无人确认永不归零）。按设备实况续推：
            // st==2 物理工作已完成 → 跳完成确认收尾；st==1 执行中 → 跳等待完成。
            // 任务号 0（task_no 无数字）不收养：CREATED 步本任务尚未写过设备，0==0 可能误认前一无数字任务的残留。
            long deviceTaskNum = readLong(appId, "status_TaskNum");
            long myTaskNum = longValue(parseTaskNum(task));
            if (myTaskNum != 0 && deviceTaskNum == myTaskNum) {
                setStep(task, st == 2 ? HandshakeStep.COMPLETE_CONFIRMED : HandshakeStep.EXECUTE_CONFIRMED);
                task.setStatus(TaskStatus.EXECUTING);
                resetStall(task);
                log.info("task {} 再收养：设备持有本任务号(status_Task={})，按实况续推", task.getTask_no(), st);
                return true;
            }
            // 设备被他人任务占用/残留：长期不就绪要人可见（有界计数报警而非无限静默等待，不 fail——等待是安全的）
            if (bumpStall(task) >= STALL_LIMIT * 10) {
                resetStall(task);
                raiseOpsAlarm(appId, TaskRecoveryScheduler.FAULT_CODE_TASK_STALE,
                        "任务 " + task.getTask_no() + " 前置检查长期不就绪：设备任务态=" + st
                                + "，设备任务号=" + deviceTaskNum + "（非本任务），队列等待中",
                        "检查设备是否有残留任务待确认（必要时 /alarm/reset-fault 复位）");
            }
            return true;
        }
        boolean ready = readInt(appId, "status_Mode") == MODE_ONLINE_AUTO
                && !connectionManager.hasActiveFault(appId);
        if (ready) {
            setStep(task, HandshakeStep.CHECKING);
            resetStall(task);
        }
        return true;
    }

    private boolean doWriteParams(TaskEntity task, Long appId) {
        // 6.18 协议要求顺序：①先写取/放货地址(排/列/层) → ②写取放货口 → ③写任务号 → ④最后写任务类型
        // 重入安全：ConfirmTask 尚未置 1，重复写同值参数无副作用。
        write(appId, "cmd_TakeCoor_Row", longValue(task.getTake_row()));
        write(appId, "cmd_TakeCoor_Column", longValue(task.getTake_column()));
        write(appId, "cmd_TakeCoor_Floor", longValue(task.getTake_floor()));
        write(appId, "cmd_PutCoor_Row", longValue(task.getPut_row()));
        write(appId, "cmd_PutCoor_Column", longValue(task.getPut_column()));
        write(appId, "cmd_PutCoor_Floor", longValue(task.getPut_floor()));
        write(appId, "cmd_PortNum", longValue(task.getPort_num()));
        write(appId, "cmd_TaskNum", longValue(parseTaskNum(task)));
        write(appId, "cmd_TaskType", intValue(task.getTask_type()));
        setStep(task, HandshakeStep.PARAMS_WRITTEN);
        task.setStatus(TaskStatus.DISPATCHED);
        task.setDispatch_time(LocalDateTime.now());
        return true;
    }

    private boolean doConfirmExecute(TaskEntity task, Long appId) {
        int st = readInt(appId, "status_Task");
        long myTaskNum = longValue(parseTaskNum(task));
        if (st == 0) {
            // 复位防护：确认前回读命令区任务号——与本任务不符（PLC 复位丢参数等）则回退重写参数，
            // 绝不对未知参数发执行确认（否则设备可能以清零坐标起动）。
            long echoed = readLong(appId, "cmd_TaskNum");
            if (echoed != myTaskNum) {
                log.warn("task {} 命令区回读不符(cmd_TaskNum={}, 本任务号={})，回退重写参数",
                        task.getTask_no(), echoed, myTaskNum);
                setStep(task, HandshakeStep.CHECKING);
                return true;
            }
            // 占位提交（cancel 竞态防护）：先以乐观锁赢得任务行版本，再写不可回滚的执行确认。
            // 并发写（管理端取消等）赢了 → claim 失败 → 本步废弃，设备一个字节都不写。
            setStep(task, HandshakeStep.EXECUTE_CONFIRMED);
            task.setStatus(TaskStatus.EXECUTING);
            if (!claim(task)) {
                log.warn("task {} 执行确认占位失败(并发写赢)，本步废弃不写设备", task.getTask_no());
                return true;
            }
            write(appId, "cmd_ConfirmTask", 1); // 执行任务
            return true;
        }
        // 重入容错（F3）：cmd_ConfirmTask=1 已写入但本步未持久化（崩溃或乐观锁冲突窗口）→ 从设备实况续推。
        // 设备级单飞下 cmd_TaskNum 由本任务写入，等值（含 0==0，task_no 无数字时）即归属本任务；不符才是异常。
        long deviceTaskNum = readLong(appId, "status_TaskNum");
        if (deviceTaskNum == myTaskNum) {
            setStep(task, st == 1 ? HandshakeStep.EXECUTE_CONFIRMED : HandshakeStep.COMPLETE_CONFIRMED);
            task.setStatus(TaskStatus.EXECUTING);
            return true;
        }
        failAndClear(task, "执行确认阶段设备任务号不匹配(status_Task=" + st
                + ", 设备任务号=" + deviceTaskNum + ", 本任务号=" + myTaskNum + ")", 0);
        return false;
    }

    private boolean doWaitComplete(TaskEntity task, Long appId) {
        int st = readInt(appId, "status_Task");
        if (st == 1) {
            write(appId, "cmd_ConfirmTask", 0); // 无意义
            setStep(task, HandshakeStep.EXECUTING);
            clearResetSpin(task);
        } else if (st == 2) {
            setStep(task, HandshakeStep.COMPLETE_CONFIRMED);
            clearResetSpin(task);
        } else if (st == 0) {
            // 设备中途复位（断电/人工复位：st 回 0、无故障码）或「占位已提交但确认未写出」的崩溃窗口：
            // 连续多轮无进展即判失败终止，避免任务无限空转、饿死该设备整个队列（在飞优先是正确性约束）。
            int rounds = resetSpin.merge(spinKey(task), 1, Integer::sum);
            if (rounds >= RESET_SPIN_ROUNDS) {
                failAndClear(task, "等待完成期间设备任务态回 0(疑似设备复位/执行确认丢失)，连续 "
                        + rounds + " 轮无进展", 0);
                return false;
            }
        }
        return true;
    }

    private boolean doConfirmComplete(TaskEntity task, Long appId) {
        // 占位提交（M11 审查 P2：stale 判败竞态）：先赢任务行版本再写完成确认——stale/取消等并发写赢了则
        // 本步废弃不写设备，下轮以最新任务态（如 failed→重派→再收养）走正确路径。
        setStep(task, HandshakeStep.CLEARED);
        if (!claim(task)) {
            setStep(task, HandshakeStep.COMPLETE_CONFIRMED);
            log.warn("task {} 完成确认占位失败(并发写赢)，本步废弃", task.getTask_no());
            return true;
        }
        // 重入安全：设备已回 status_Task=0 时重复写 confirm=2 无副作用（设备级单飞保证无下一任务被误确认）。
        write(appId, "cmd_ConfirmTask", 2); // 任务完成确认
        return true;
    }

    private boolean doClear(TaskEntity task, Long appId) {
        // 重入兜底：claim 成功但确认未写出（崩溃窗口）时设备仍停 st=2——清零前补发完成确认
        //（设备已回 0 时按 6.18 协议写 2 无副作用）。
        write(appId, "cmd_ConfirmTask", 2);
        // 重入安全：清零幂等。
        for (String f : new String[]{"cmd_TakeCoor_Row", "cmd_TakeCoor_Column", "cmd_TakeCoor_Floor",
                "cmd_PutCoor_Row", "cmd_PutCoor_Column", "cmd_PutCoor_Floor",
                "cmd_PortNum", "cmd_TaskNum", "cmd_TaskType", "cmd_ConfirmTask"}) {
            write(appId, f, 0);
        }
        setStep(task, HandshakeStep.DONE);
        task.setStatus(TaskStatus.COMPLETED);
        task.setFinish_time(LocalDateTime.now());
        clearResetSpin(task);
        return false;
    }

    private void failAndClear(TaskEntity task, String message, int errorCode) {
        clearResetSpin(task);
        fail(task, message, errorCode);
    }

    private String spinKey(TaskEntity task) {
        return task.getId() != null ? "id:" + task.getId() : "no:" + task.getTask_no();
    }

    private void clearResetSpin(TaskEntity task) {
        resetSpin.remove(spinKey(task));
    }

    private HandshakeStep currentStep(TaskEntity task) {
        String s = task.getHandshake_step();
        if (s == null || s.isBlank()) {
            return HandshakeStep.CREATED;
        }
        try {
            return HandshakeStep.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return HandshakeStep.CREATED;
        }
    }

    private void setStep(TaskEntity task, HandshakeStep step) {
        task.setHandshake_step(step.name());
    }

    private static int intValue(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static long longValue(Long v) {
        return v == null ? 0L : v;
    }

    private static Long parseTaskNum(TaskEntity task) {
        try {
            return task.getTask_no() == null ? 0L : Long.parseLong(task.getTask_no().replaceAll("\\D", ""));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
