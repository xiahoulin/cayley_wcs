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

/** 验证堆垛机三段握手状态机：驱动任务至完成；以及执行中故障判失败。 */
class StackerHandshakeTest {
    private static final long APP_ID = 1L;
    private static final long PROTOCOL_ID = 200L;

    @Test
    void handshakeDrivesTaskToCompletion() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task();

        sm.advance(task); // CREATED -> CHECKING
        sm.advance(task); // CHECKING -> PARAMS_WRITTEN
        sm.advance(task); // -> EXECUTE_CONFIRMED (写 confirm=1, 设备 task=1)
        assertThat(task.getStatus()).isEqualTo("executing");
        sm.advance(task); // -> EXECUTING (写 confirm=0)

        StackerDeviceState device = h.registry.find(APP_ID).orElseThrow();
        device.tick();
        device.tick();   // 设备 task -> 2

        sm.advance(task); // -> COMPLETE_CONFIRMED
        sm.advance(task); // -> CLEARED (写 confirm=2)
        boolean more = sm.advance(task); // -> DONE (清零)

        assertThat(more).isFalse();
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.DONE.name());
        assertThat(task.getStatus()).isEqualTo("completed");
        assertThat(task.getFinish_time()).isNotNull();
        h.manager.shutdown();
    }

    @Test
    void faultDuringExecutionFailsTask() {
        Harness h = new Harness();
        StackerHandshakeStateMachine sm = new StackerHandshakeStateMachine(h.manager);
        TaskEntity task = task();

        sm.advance(task); // CHECKING
        sm.advance(task); // PARAMS_WRITTEN
        sm.advance(task); // EXECUTE_CONFIRMED

        h.registry.find(APP_ID).orElseThrow().injectFault(18); // 平移轴驱动器错误
        boolean more = sm.advance(task);

        assertThat(more).isFalse();
        assertThat(task.getHandshake_step()).isEqualTo(HandshakeStep.FAILED.name());
        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getError_code()).isEqualTo(18L);
        h.manager.shutdown();
    }

    private TaskEntity task() {
        TaskEntity t = new TaskEntity();
        t.setApp_id(APP_ID);
        t.setTask_no("1001");
        t.setTask_type("1"); // 入库
        t.setTake_row(1L);
        t.setTake_column(3L);
        t.setTake_floor(4L);
        t.setPut_row(2L);
        t.setPut_column(5L);
        t.setPut_floor(6L);
        t.setPort_num(1L);
        t.setPriority(0L);
        t.setStatus("pending");
        t.setHandshake_step(HandshakeStep.CREATED.name());
        return t;
    }

    /** 建好一个已连接(sim)的连接管理器。 */
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
                    "cmd_ConfirmTask", "PLC_Heart", "status_Mode", "status_Task", "status_TaskTypeFeedback",
                    "status_CurrentColumnNum", "status_CurrentFloorNum", "status_ErrorCode"}) {
                ProtocolPointEntity p = new ProtocolPointEntity();
                p.setField_name(f);
                p.setData_type("INT");
                list.add(p);
            }
            return list;
        }
    }
}
