package com.cayleywcs.task;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.task.entity.TaskEntity;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 堆垛机三段握手状态机（6.18 协议）。每次 {@link #advance} 推进一步，通过 {@link ConnectionManager} 读写点位。
 * 检查阶段(模式=联机自动/无故障/无任务) → 下发参数 → 执行确认 → 等待完成 → 完成确认 → 清零。
 * 执行期间读到 status_ErrorCode!=0 即判失败（报警在 M5 联动）。
 */
@Component
public class StackerHandshakeStateMachine {
    private static final Logger log = LoggerFactory.getLogger(StackerHandshakeStateMachine.class);
    private static final int MODE_ONLINE_AUTO = 2;

    private final ConnectionManager connectionManager;

    public StackerHandshakeStateMachine(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /** 推进一步。返回 true 表示任务仍在进行；false 表示已到终态(DONE/FAILED)。 */
    public boolean advance(TaskEntity task) {
        Long appId = task.getApp_id();
        HandshakeStep step = currentStep(task);
        try {
            // 故障检查：支持标量(仿真)和数组(真实PLC ARRAY[1..60])。任一非零即判故障。
            int firstErrorCode = readFirstFaultCode(appId);
            if (firstErrorCode != 0 && step != HandshakeStep.DONE && step != HandshakeStep.FAILED) {
                fail(task, "设备故障，故障码=" + firstErrorCode, firstErrorCode);
                return false;
            }
            return switch (step) {
                case CREATED -> doCheck(task, appId);
                case CHECKING -> doWriteParams(task, appId);
                case PARAMS_WRITTEN -> doConfirmExecute(task, appId);
                case EXECUTE_CONFIRMED, EXECUTING -> doWaitComplete(task, appId);
                case COMPLETE_CONFIRMED -> doConfirmComplete(task, appId);
                case CLEARED -> doClear(task, appId);
                case DONE, FAILED -> false;
            };
        } catch (RuntimeException ex) {
            log.warn("task {} handshake error at {}: {}", task.getTask_no(), step, ex.getMessage());
            fail(task, ex.getMessage(), task.getError_code() == null ? 0 : task.getError_code().intValue());
            return false;
        }
    }

    private boolean doCheck(TaskEntity task, Long appId) {
        boolean ready = readInt(appId, "status_Mode") == MODE_ONLINE_AUTO
                && readInt(appId, "status_Task") == 0
                && !connectionManager.hasActiveFault(appId);
        if (ready) {
            setStep(task, HandshakeStep.CHECKING);
        }
        return true;
    }

    private boolean doWriteParams(TaskEntity task, Long appId) {
        // 6.18 协议要求顺序：①先写取/放货地址(排/列/层) → ②写取放货口 → ③写任务号 → ④最后写任务类型
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
        task.setStatus("dispatched");
        task.setDispatch_time(LocalDateTime.now());
        return true;
    }

    private boolean doConfirmExecute(TaskEntity task, Long appId) {
        if (readInt(appId, "status_Task") == 0) {
            write(appId, "cmd_ConfirmTask", 1); // 执行任务
            setStep(task, HandshakeStep.EXECUTE_CONFIRMED);
            task.setStatus("executing");
        }
        return true;
    }

    private boolean doWaitComplete(TaskEntity task, Long appId) {
        int st = readInt(appId, "status_Task");
        if (st == 1) {
            write(appId, "cmd_ConfirmTask", 0); // 无意义
            setStep(task, HandshakeStep.EXECUTING);
        } else if (st == 2) {
            setStep(task, HandshakeStep.COMPLETE_CONFIRMED);
        }
        return true;
    }

    private boolean doConfirmComplete(TaskEntity task, Long appId) {
        write(appId, "cmd_ConfirmTask", 2); // 任务完成确认
        setStep(task, HandshakeStep.CLEARED);
        return true;
    }

    private boolean doClear(TaskEntity task, Long appId) {
        for (String f : new String[]{"cmd_TakeCoor_Row", "cmd_TakeCoor_Column", "cmd_TakeCoor_Floor",
                "cmd_PutCoor_Row", "cmd_PutCoor_Column", "cmd_PutCoor_Floor",
                "cmd_PortNum", "cmd_TaskNum", "cmd_TaskType", "cmd_ConfirmTask"}) {
            write(appId, f, 0);
        }
        setStep(task, HandshakeStep.DONE);
        task.setStatus("completed");
        task.setFinish_time(LocalDateTime.now());
        return false;
    }

    private void fail(TaskEntity task, String message, int errorCode) {
        setStep(task, HandshakeStep.FAILED);
        task.setStatus("failed");
        task.setError_code((long) errorCode);
        task.setFinish_time(LocalDateTime.now());
        log.warn("task {} failed: {}", task.getTask_no(), message);
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

    private int readInt(Long appId, String field) {
        Object v = connectionManager.readPoint(appId, field);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 读取故障码：支持标量(仿真器返回 Number)和数组(真实PLC返回 List)。返回首个非零码，无故障返回0。 */
    private int readFirstFaultCode(Long appId) {
        Object v = connectionManager.readPoint(appId, "status_ErrorCode");
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

    private void write(Long appId, String field, Object value) {
        connectionManager.writePoint(appId, field, value);
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
