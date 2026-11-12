package com.cayleywcs.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cayleywcs.adapter.ProtocolAdapterFactory;
import com.cayleywcs.adapter.sim.SimAdapterProvider;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.simulator.StackerDeviceState;
import com.cayleywcs.simulator.StackerSimulatorRegistry;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 握手并发与重入回归约束：
 * ① cancel 竞态占位提交——claim 失败必须一个字节都不写设备；
 * ② 传输/生命周期异常不落终态——连接拆除后 advance 放弃本步、任务保持在飞；
 * ③ task_no 无数字的任务重入按 0==0 等值续推，不误判失败；
 * ④ 确认前命令区回读不符 → 回退重写参数（复位丢参防护），并能自愈跑完；
 * ⑤ 等待完成期间设备任务态回 0 连续多轮 → 判失败终止，不无限空转饿死队列。
 */
class StackerHandshakeHardeningTest {
    private static final long APP_ID = 1L;
    private static final long PROTOCOL_ID = 200L;

    @Test
    void claimLossWritesNothingToDevice() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskService persister = mock(TaskService.class);
        when(persister.save(any())).thenReturn(false); // 并发写（如取消）赢了
        sm.setTaskPersister(persister);
        TaskEntity task = task("1001");

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        boolean more = sm.advance(task); // 执行确认步：占位失败 → 废弃本步

        assertThat(more).isTrue();
        StackerDeviceState device = h.registry.find(APP_ID).orElseThrow();
        assertThat(((Number) device.get("cmd_ConfirmTask")).intValue())
                .as("占位失败后不得写执行确认").isEqualTo(0);
        assertThat(((Number) device.get("status_Task")).intValue())
                .as("设备不得被启动").isEqualTo(0);
        h.manager.shutdown();
    }

    @Test
    void transportExceptionAbandonsStepInsteadOfFailing() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task("1001");

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        h.manager.close(APP_ID); // 连接拆除（停机/断连场景）：readPoint 将抛 CONN_NOT_FOUND

        boolean more = sm.advance(task);

        assertThat(more).as("传输异常应放弃本步而非终态").isTrue();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DISPATCHED);
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.PARAMS_WRITTEN.name());
        h.manager.shutdown();
    }

    @Test
    void digitlessTaskNoResumesByAllocatedNativeNumber() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task("URGENT-A"); // 业务编号无数字，仍使用独立原生任务号1

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        // 崩溃窗口：确认已写到设备（设备以分配的原生任务号开跑），任务行停在 PARAMS_WRITTEN
        h.manager.writePoint(APP_ID, "cmd_ConfirmTask", 1);

        boolean more = sm.advance(task);

        assertThat(more).isTrue();
        assertThat(task.getHandshake_step())
                .as("独立原生任务号一致时续推")
                .isEqualTo(HandshakeStep.EXECUTE_CONFIRMED.name());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXECUTING);
        h.manager.shutdown();
    }

    @Test
    void commandAreaMismatchRegressesAndRecovers() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task("1001");

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        h.manager.writePoint(APP_ID, "cmd_TaskNum", 777); // 模拟 PLC 复位丢参/命令区被改

        sm.advance(task); // 回读不符 → 回退 CHECKING，且不得写确认
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.CHECKING.name());
        StackerDeviceState device = h.registry.find(APP_ID).orElseThrow();
        assertThat(((Number) device.get("status_Task")).intValue()).isEqualTo(0);

        // 自愈：重写参数 → 确认 → 执行 → 完成
        sm.advance(task); // doWriteParams（cmd_TaskNum 回 1001）→ PARAMS_WRITTEN
        sm.advance(task); // 回读一致 → 确认执行
        sm.advance(task); // EXECUTING
        device.tick();
        device.tick();
        sm.advance(task); // COMPLETE_CONFIRMED
        sm.advance(task); // CLEARED
        boolean more = sm.advance(task); // DONE

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        h.manager.shutdown();
    }

    @Test
    void readoptionResumesDeviceHeldTaskAfterStaleRetry() {
        // 停滞判 failed 期间设备自主完成停 status_Task=2 → 重派（同任务号回 CREATED）
        // → doCheck 再收养 → 补发完成确认按完成收尾，设备回 0，队列解锁
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task("1001");

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        sm.advance(task); // 确认执行：设备 status_Task=1
        StackerDeviceState device = h.registry.find(APP_ID).orElseThrow();
        device.tick();
        device.tick();    // 设备自主完成：status_Task=2，等 confirm=2

        // 模拟 stale 判 failed 后重派：任务重置回 CREATED/pending（resetForRetry 语义），设备状态原样
        task.setStatus(TaskStatus.PENDING);
        task.setHandshake_step(HandshakeStep.CREATED.name());

        sm.advance(task); // doCheck 再收养：st=2 且设备任务号==本任务号 → COMPLETE_CONFIRMED
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.COMPLETE_CONFIRMED.name());
        sm.advance(task); // 补发完成确认 → CLEARED（设备回 0）
        boolean more = sm.advance(task); // DONE

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(((Number) device.get("status_Task")).intValue())
                .as("设备解锁，后续任务可跑").isEqualTo(0);

        // 队列解锁验证：下一个任务正常跑完
        TaskEntity next = task("1002");
        sm.advance(next); // CHECKING（st==0 通过）
        sm.advance(next); // PARAMS_WRITTEN
        sm.advance(next); // 确认执行
        device.tick();
        device.tick();
        sm.advance(next); // COMPLETE_CONFIRMED
        sm.advance(next); // CLEARED
        assertThat(sm.advance(next)).isFalse();
        assertThat(next.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        h.manager.shutdown();
    }

    @Test
    void foreignDeviceTaskIsNotAdoptedAndRaisesOpsAlarmAfterBoundedWait() {
        // 设备被他人任务占用（任务号不匹配）：不收养、不 fail，有界等待后 raise 运维报警
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        com.cayleywcs.alarm.AlarmService alarm = org.mockito.Mockito.mock(com.cayleywcs.alarm.AlarmService.class);
        sm.setAlarmService(alarm);
        // 设备在跑任务号 9999
        h.manager.writePoint(APP_ID, "cmd_TaskNum", 9999);
        h.manager.writePoint(APP_ID, "cmd_ConfirmTask", 1);

        TaskEntity task = task("1001");
        for (int i = 0; i < 31; i++) {
            assertThat(sm.advance(task)).as("等待非终态").isTrue();
        }
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        org.mockito.Mockito.verify(alarm, org.mockito.Mockito.atLeastOnce())
                .raise(org.mockito.ArgumentMatchers.eq(APP_ID),
                        org.mockito.ArgumentMatchers.eq(com.cayleywcs.task.TaskRecoveryScheduler.FAULT_CODE_TASK_STALE),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        h.manager.shutdown();
    }

    @Test
    void consecutiveZeroTaskRoundsFailTheTask() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task("1001");

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        // 模拟「占位已提交但确认未写出」的崩溃窗口 / 设备中途复位：步态在 EXECUTE_CONFIRMED，设备 st=0 无故障
        task.setHandshake_step(HandshakeStep.EXECUTE_CONFIRMED.name());
        task.setStatus(TaskStatus.EXECUTING);

        assertThat(sm.advance(task)).isTrue();  // 第 1 轮：等待
        assertThat(sm.advance(task)).isTrue();  // 第 2 轮：等待
        boolean more = sm.advance(task);        // 第 3 轮：判失败终止

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.FAILED.name());
        h.manager.shutdown();
    }

    private TaskEntity task(String taskNo) {
        TaskEntity t = new TaskEntity();
        t.setApp_id(APP_ID);
        t.setTask_no(taskNo);
        t.setTask_type("1");
        t.setTake_row(1L);
        t.setTake_column(3L);
        t.setTake_floor(4L);
        t.setPut_row(2L);
        t.setPut_column(5L);
        t.setPut_floor(6L);
        t.setPort_num(1L);
        t.setPriority(0L);
        t.setStatus(TaskStatus.PENDING);
        t.setHandshake_step(HandshakeStep.CREATED.name());
        t.setPayload(RequestPayloads.migrate(t.getTask_type(),t.getTake_row(),t.getTake_column(),t.getTake_floor(),t.getPut_row(),t.getPut_column(),t.getPut_floor(),t.getPort_num(),null));
        t.setNative_task_num(t.getTask_no().matches("[0-9]+")?Long.valueOf(t.getTask_no()):1L);
        return t;
    }

    /** 已连接(sim)的连接管理器；点位表含 status_TaskNum（归属核对）。 */
    private static final class Harness {
        final StackerSimulatorRegistry registry = new StackerSimulatorRegistry();
        final ConnectionManager manager;

        Harness() {
            ProtocolAdapterFactory factory = new ProtocolAdapterFactory(List.of(new SimAdapterProvider(registry)));
            ApplicationService appService = mock(ApplicationService.class);
            ProtocolService protoService = mock(ProtocolService.class);

            ApplicationEntity app = new ApplicationEntity();
            app.setId(APP_ID);
            app.setApp_code("SIM01");
            app.setApp_name("堆垛机仿真");
            app.setProtocol_id(PROTOCOL_ID);
            app.setHeartbeat_interval_ms(60000L);

            ProtocolEntity protocol = new ProtocolEntity();
            protocol.setId(PROTOCOL_ID);
            protocol.setProtocol_code("STACKER_SIM");
            protocol.setProtocol_type("sim");

            when(appService.validateForConnect(APP_ID)).thenReturn(app);
            lenient().when(protoService.getById(PROTOCOL_ID)).thenReturn(protocol);
            lenient().when(protoService.listPoints(PROTOCOL_ID)).thenReturn(points());

            manager = new ConnectionManager(appService, protoService, factory, event -> { }, null,
                    4, 5, 12000L, 5, 0L);
            manager.open(APP_ID);
        }

        private List<ProtocolPointEntity> points() {
            List<ProtocolPointEntity> list = new ArrayList<>();
            for (String f : new String[]{
                    "WCS_Heart", "cmd_TaskType", "cmd_TakeCoor_Row", "cmd_TakeCoor_Column", "cmd_TakeCoor_Floor",
                    "cmd_PutCoor_Row", "cmd_PutCoor_Column", "cmd_PutCoor_Floor", "cmd_PortNum", "cmd_TaskNum",
                    "cmd_ConfirmTask", "PLC_Heart", "status_Mode", "status_Task", "status_TaskNum",
                    "status_TaskTypeFeedback", "status_CurrentColumnNum", "status_CurrentFloorNum", "status_ErrorCode"}) {
                ProtocolPointEntity p = new ProtocolPointEntity();
                p.setField_name(f);
                p.setData_type("status_ErrorCode".equals(f)?"ARRAY[60] OF INT":"INT");
                list.add(p);
            }
            return list;
        }
    }
}
