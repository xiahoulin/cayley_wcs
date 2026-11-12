package com.cayleywcs.task;

import static org.assertj.core.api.Assertions.assertThat;
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
 * F3 重入修复：cmd_ConfirmTask=1 已写入设备、但 handshake_step 未持久化（崩溃或乐观锁冲突窗口）后，
 * 状态机必须能从设备实况续推而不是死等 status_Task==0；设备在跑别人的任务时须判失败带诊断。
 */
class StackerHandshakeReentryTest {
    private static final long APP_ID = 1L;
    private static final long PROTOCOL_ID = 200L;

    @Test
    void resumesWhenConfirmWrittenButStepNotPersisted() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task();

        sm.advance(task); // CREATED -> CHECKING
        sm.advance(task); // CHECKING -> PARAMS_WRITTEN（含 cmd_TaskNum=1001）

        // 模拟崩溃窗口：确认已写到设备（设备开跑，status_Task=1，status_TaskNum=1001），但任务行仍停在 PARAMS_WRITTEN
        h.manager.writePoint(APP_ID, "cmd_ConfirmTask", 1);
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.PARAMS_WRITTEN.name());

        sm.advance(task); // 修复点：识别设备任务号归属，续推而非死等
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.EXECUTE_CONFIRMED.name());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXECUTING);

        StackerDeviceState device = h.registry.find(APP_ID).orElseThrow();
        sm.advance(task); // EXECUTING
        device.tick();
        device.tick();    // 设备 task -> 2
        sm.advance(task); // COMPLETE_CONFIRMED
        sm.advance(task); // CLEARED
        boolean more = sm.advance(task); // DONE

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        h.manager.shutdown();
    }

    @Test
    void resumesWhenDeviceAlreadyFinishedInWindow() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task();

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        h.manager.writePoint(APP_ID, "cmd_ConfirmTask", 1);
        StackerDeviceState device = h.registry.find(APP_ID).orElseThrow();
        device.tick();
        device.tick();    // 窗口期内设备已执行完：status_Task=2

        sm.advance(task); // 修复点：直接进入完成确认
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.COMPLETE_CONFIRMED.name());

        sm.advance(task); // CLEARED
        boolean more = sm.advance(task); // DONE
        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        h.manager.shutdown();
    }

    @Test
    void failsWithDiagnosticWhenDeviceRunsForeignTask() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task();

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        // 设备被别的来源启动了另一个任务号
        h.manager.writePoint(APP_ID, "cmd_TaskNum", 9999);
        h.manager.writePoint(APP_ID, "cmd_ConfirmTask", 1);

        boolean more = sm.advance(task);

        assertThat(more).isFalse();
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.FAILED.name());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        h.manager.shutdown();
    }

    private TaskEntity task() {
        TaskEntity t = new TaskEntity();
        t.setApp_id(APP_ID);
        t.setTask_no("1001");
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

    /** 建好一个已连接(sim)的连接管理器；点位表含 status_TaskNum（重入归属核对用）。 */
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
