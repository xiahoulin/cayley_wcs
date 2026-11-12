package com.cayleywcs.device;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 设备 handler 基类：下沉点位读写、故障码解析、payload 取值、占位提交与死等检测等通用助手；子类只写设备专属状态机。
 * 约定：各设备的步枚举写入 wcs_task.handshake_step（varchar，handler 私有语义），终态步名统一为 "DONE"/"FAILED"。
 *
 * 并发/重入契约（新设备 handler 同样适用）：
 * - 对设备的不可回滚写（启动/执行确认）前先 {@link #claim} 占位提交，管理端并发取消赢了则本步废弃、设备零写入；
 * - 传输/生命周期异常（连接拆除、读写超时、停机中断）经 {@link #advanceGuarded} 只放弃本步、不落终态；
 * - 等待期间设备意外回空闲用 {@link #bumpStall}/{@link #STALL_LIMIT} 判死等，避免无限空转饿死设备队列。
 */
public abstract class AbstractDeviceHandler implements DeviceHandler {
    /** 终态步名（各设备步枚举须含同名项）。 */
    protected static final String STEP_DONE = "DONE";
    protected static final String STEP_FAILED = "FAILED";
    /** 等待期间连续无进展判死等的轮数阈值。 */
    protected static final int STALL_LIMIT = 3;

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ConnectionManager connectionManager;

    private TaskService taskPersister;
    private AlarmService alarmService;
    private com.cayleywcs.task.TaskExecutionService execution;

    @Autowired(required=false)
    public void setExecution(com.cayleywcs.task.TaskExecutionService execution) { this.execution=execution; }

    protected void beginPhysical(TaskEntity task) {
        if (execution!=null) execution.assertCanStart(task);
    }

    protected void captureResult(TaskEntity task) {
        if (execution!=null) execution.captureResult(task);
    }

    protected long nativeNumber(TaskEntity task) {
        if (task.getNative_task_num()!=null) return task.getNative_task_num();
        if (execution!=null) throw new WcsException(ErrorCode.TASK_INVALID,"缺少已分配原生设备任务号");
        String digits=task.getTask_no()==null?"":task.getTask_no().replaceAll("\\D","");
        try { return Long.parseLong(digits); } catch (NumberFormatException ex) { return 0; }
    }
    private final Map<String, Integer> stall = new ConcurrentHashMap<>();

    protected AbstractDeviceHandler(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Autowired(required = false)
    public void setTaskPersister(TaskService taskPersister) {
        this.taskPersister = taskPersister;
    }

    @Autowired(required = false)
    public void setAlarmService(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    /** 运维报警（去重 raise）：长期无进展等需要人可见的状态。无 AlarmService（单测直接 new）时仅日志。 */
    protected void raiseOpsAlarm(Long appId, long faultCode, String message, String suggestion) {
        log.warn(message);
        if (alarmService != null) {
            alarmService.raise(appId, faultCode, "warn", message, suggestion);
        }
    }

    /** 步派发函数：接收任务、appId、当前步名，返回 true=仍在进行/false=终态。 */
    @FunctionalInterface
    public interface StepDispatcher {
        boolean dispatch(TaskEntity task, Long appId, String step);
    }

    /**
     * 通用推进模板：故障闸（非终态读到故障码即 fail）+ 传输/生命周期异常放弃本步（不落终态，下轮续推）。
     * 子类 advance() 只需 {@code return advanceGuarded(task, (t, appId, step) -> switch(...))}。
     */
    protected boolean advanceGuarded(TaskEntity task, StepDispatcher dispatcher) {
        Long appId = task.getApp_id();
        String step = task.getHandshake_step();
        if (step == null || step.isBlank()) {
            step = "CREATED";
        }
        try {
            int fault = readFirstFaultCode(appId);
            if (fault != 0 && !STEP_DONE.equals(step) && !STEP_FAILED.equals(step)) {
                fail(task, "设备故障，故障码=" + fault, fault);
                return false;
            }
            return dispatcher.dispatch(task, appId, step);
        } catch (RuntimeException ex) {
            log.warn("task {} {} 步异常，本步放弃下轮重试: {}", task.getTask_no(), step, ex.getMessage());
            return true;
        }
    }

    /**
     * 占位提交：在对设备发出不可回滚的写之前，先以乐观锁把意图状态落库赢得版本——
     * 管理端并发写（如取消）赢了则返回 false，本步必须废弃且不得再写设备。
     * 无持久化器（单测直接 new）时视为成功，退化为旧行为。
     *
     * 关键：MyBatis-Plus OptimisticLocker 拦截器在 updateById 时**无论命中几行都把实体内存
     * version 就地 +1**。若不还原，claim 失败后引擎兜底 `save` 会用被 +1 的版本恰好命中「取消方已提交(version+1)」
     * 的行 → 静默覆盖复活已取消任务。故失败时必须把 version 还原为调用前值，令引擎兜底 save 也 0 行、安全丢弃本步。
     */
    protected boolean claim(TaskEntity task) {
        if (taskPersister == null) {
            return true;
        }
        Long before = task.getVersion();
        boolean ok = taskPersister.save(task);
        if (!ok) {
            task.setVersion(before);
        }
        return ok;
    }

    protected int readInt(Long appId, String field) {
        Object v = connectionManager.readPoint(appId, field);
        return v instanceof Number n ? n.intValue() : 0;
    }

    protected long readLong(Long appId, String field) {
        Object v = connectionManager.readPoint(appId, field);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    protected void write(Long appId, String field, Object value) {
        connectionManager.writePoint(appId, field, value);
    }

    /**
     * 读故障码：支持标量(仿真)与数组(真实 PLC ARRAY[1..60])，返回首个非零码，无故障为 0。
     * 协议未配置 status_ErrorCode 点位时视为无故障（跳过故障闸），不因配置缺失让每轮抛异常无限空转；
     * 连接不存在等传输异常照常抛出，交由 {@link #advanceGuarded} 放弃本步。
     */
    protected int readFirstFaultCode(Long appId) {
        Object v;
        try {
            v = connectionManager.readPoint(appId, "status_ErrorCode");
        } catch (WcsException ex) {
            if (ex.getCode() == ErrorCode.BAD_REQUEST.code()) {
                return 0; // 点位不存在：无故障闸
            }
            throw ex;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof Iterable<?> iter) {
            for (Object elem : iter) {
                int code = elem instanceof Number num ? num.intValue() : 0;
                if (code != 0) {
                    return code;
                }
            }
        }
        return 0;
    }

    /** 从任务 payload 取 long（缺省返回 def）。新设备任务参数统一走 payload，不加专用列。 */
    protected long payloadLong(TaskEntity task, String key, long def) {
        Object v = payloadValue(task, key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? def : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** 从任务 payload 取必填 long：缺失/不可解析返回 null（供 handler 校验后 fail，避免用 0 号站/0 层等垃圾值命令设备）。 */
    protected Long payloadLongOrNull(TaskEntity task, String key) {
        Object v = payloadValue(task, key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    protected String payloadString(TaskEntity task, String key, String def) {
        Object v = payloadValue(task, key);
        return v == null ? def : String.valueOf(v).trim();
    }

    private Object payloadValue(TaskEntity task, String key) {
        Map<String, Object> p = task.getPayload();
        return p == null ? null : p.get(key);
    }

    /** 任务号数字部分（无数字返回 0；设备级单飞下用于归属核对）。 */
    protected static long taskNum(TaskEntity task) {
        try {
            return task.getTask_no() == null ? 0L : Long.parseLong(task.getTask_no().replaceAll("\\D", ""));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /** 死等计数：同一任务连续调用递增，返回累计轮数。检测到进展应调 {@link #resetStall}。 */
    protected int bumpStall(TaskEntity task) {
        return stall.merge(taskKey(task), 1, Integer::sum);
    }

    protected void resetStall(TaskEntity task) {
        stall.remove(taskKey(task));
    }

    /** 任务判失败（终态）。 */
    protected void fail(TaskEntity task, String message, int errorCode) {
        resetStall(task);
        task.setHandshake_step(STEP_FAILED);
        task.setStatus(TaskStatus.FAILED);
        task.setError_code((long) errorCode);
        task.setFinish_time(LocalDateTime.now());
        log.warn("task {} failed: {}", task.getTask_no(), message);
    }

    private static String taskKey(TaskEntity task) {
        return task.getId() != null ? "id:" + task.getId() : "no:" + task.getTask_no();
    }
}
