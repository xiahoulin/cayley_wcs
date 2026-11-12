package com.cayleywcs.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cayleywcs.adapter.ProtocolAdapterFactory;
import com.cayleywcs.adapter.sim.SimAdapterProvider;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.autodoor.AutoDoorDeviceHandler;
import com.cayleywcs.device.conveyor.ConveyorDeviceHandler;
import com.cayleywcs.device.lift.LiftDeviceHandler;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.simulator.DeviceSim;
import com.cayleywcs.simulator.DeviceSimFactory;
import com.cayleywcs.simulator.DeviceSimRegistry;
import com.cayleywcs.task.HandshakeStep;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M9 新设备类型端到端（无硬件）：ConnectionManager → SimAdapter（按 device_type 建模）→ 内存仿真，
 * 每类设备驱动任务至完成 + 执行期注入故障判失败，验证设备类型 SPI 全链路可插拔。
 */
class NewDeviceSimEndToEndTest {
    private static final long APP_ID = 1L;
    private static final long PROTOCOL_ID = 200L;

    // ---------- 输送线 ----------
    @Test
    void conveyorRunsToCompletion() {
        Harness h = new Harness("conveyor", List.of(
                "WCS_Heart", "cmd_DestStation", "cmd_TaskNum", "cmd_Start", "cmd_ResetFault",
                "PLC_Heart", "status_Mode", "status_Task", "status_TaskNum", "status_Position",
                "status_Occupied", "status_ErrorCode"));
        ConveyorDeviceHandler handler = new ConveyorDeviceHandler(h.manager);
        TaskEntity task = task("5001", Map.of("cmd_DestStation", 7));

        handler.advance(task); // CREATED -> CHECKING
        handler.advance(task); // CHECKING -> MOVING（写目的站+启动）
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXECUTING);
        DeviceSim device = h.device();
        device.tick();
        device.tick();         // 运输到位：status_Task=2
        task.setResult(Map.of("quality","good","payload",device.snapshot())); // 本纯状态机测试显式提供已采集结果，真实持久化由Spring集成验证
        assertThat(handler.advance(task)).isTrue(); // 完成反馈先落清理阶段
        boolean more = handler.advance(task); // CLEARING -> DONE

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(((Number) device.get("status_Position")).intValue()).isEqualTo(7);
        h.manager.shutdown();
    }

    @Test
    void conveyorFaultFailsTask() {
        Harness h = new Harness("conveyor", List.of(
                "WCS_Heart", "cmd_DestStation", "cmd_TaskNum", "cmd_Start", "cmd_ResetFault",
                "PLC_Heart", "status_Mode", "status_Task", "status_TaskNum", "status_Position",
                "status_Occupied", "status_ErrorCode"));
        ConveyorDeviceHandler handler = new ConveyorDeviceHandler(h.manager);
        TaskEntity task = task("5002", Map.of("cmd_DestStation", 3));

        handler.advance(task); // CHECKING
        handler.advance(task); // MOVING
        h.device().injectFault(2); // 变频器故障
        boolean more = handler.advance(task);

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getError_code()).isEqualTo(2L);
        h.manager.shutdown();
    }

    // ---------- 电梯 ----------
    @Test
    void liftRunsToCompletion() {
        Harness h = new Harness("lift", List.of(
                "WCS_Heart", "cmd_TargetFloor", "cmd_TaskNum", "cmd_Go", "cmd_ResetFault",
                "PLC_Heart", "status_Mode", "status_Task", "status_TaskNum", "status_CurrentFloor",
                "status_DoorState", "status_ErrorCode"));
        LiftDeviceHandler handler = new LiftDeviceHandler(h.manager);
        TaskEntity task = task("6001", Map.of("cmd_TargetFloor", 5));

        handler.advance(task); // CHECKING
        handler.advance(task); // MOVING（写目标层+启动）
        DeviceSim device = h.device();
        device.tick();
        device.tick();         // 到层开门：status_Task=2, status_DoorState=1
        task.setResult(Map.of("quality","good","payload",device.snapshot())); // 本纯状态机测试显式提供已采集结果，真实持久化由Spring集成验证
        assertThat(handler.advance(task)).isTrue(); // 完成反馈先落清理阶段
        boolean more = handler.advance(task); // CLEARING -> DONE

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(((Number) device.get("status_CurrentFloor")).intValue()).isEqualTo(5);
        h.manager.shutdown();
    }

    @Test
    void liftFaultFailsTask() {
        Harness h = new Harness("lift", List.of(
                "WCS_Heart", "cmd_TargetFloor", "cmd_TaskNum", "cmd_Go", "cmd_ResetFault",
                "PLC_Heart", "status_Mode", "status_Task", "status_TaskNum", "status_CurrentFloor",
                "status_DoorState", "status_ErrorCode"));
        LiftDeviceHandler handler = new LiftDeviceHandler(h.manager);
        TaskEntity task = task("6002", Map.of("cmd_TargetFloor", 2));

        handler.advance(task); // CHECKING
        handler.advance(task); // MOVING
        h.device().injectFault(1); // 门联锁未闭合
        boolean more = handler.advance(task);

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getError_code()).isEqualTo(1L);
        h.manager.shutdown();
    }

    // ---------- 自动门 ----------
    @Test
    void autoDoorOpensToCompletion() {
        Harness h = new Harness("auto_door", List.of(
                "WCS_Heart", "cmd_Open", "cmd_Close", "cmd_ResetFault",
                "PLC_Heart", "status_Mode", "status_DoorState", "status_ErrorCode"));
        AutoDoorDeviceHandler handler = new AutoDoorDeviceHandler(h.manager);
        TaskEntity task = task("7001", Map.of("cmd_Open",1,"cmd_Close",0));

        handler.advance(task); // CREATED -> COMMANDING（写开门 → 开门中）
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXECUTING);
        h.device().tick();     // 开门到位：status_DoorState=1
        boolean more = handler.advance(task); // DONE

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(((Number) h.device().get("status_DoorState")).intValue()).isEqualTo(1);
        h.manager.shutdown();
    }

    @Test
    void autoDoorObstacleFailsTask() {
        Harness h = new Harness("auto_door", List.of(
                "WCS_Heart", "cmd_Open", "cmd_Close", "cmd_ResetFault",
                "PLC_Heart", "status_Mode", "status_DoorState", "status_ErrorCode"));
        AutoDoorDeviceHandler handler = new AutoDoorDeviceHandler(h.manager);
        TaskEntity task = task("7002", Map.of("cmd_Open",1,"cmd_Close",0));

        handler.advance(task); // COMMANDING
        h.device().injectFault(1); // 障碍物检测
        boolean more = handler.advance(task);

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getError_code()).isEqualTo(1L);
        h.manager.shutdown();
    }

    private TaskEntity task(String taskNo, Map<String, Object> payload) {
        TaskEntity t = new TaskEntity();
        t.setApp_id(APP_ID);
        t.setTask_no(taskNo);
        t.setTask_type("1");
        t.setPriority(0L);
        t.setStatus(TaskStatus.PENDING);
        t.setHandshake_step(HandshakeStep.CREATED.name());
        t.setPayload(new java.util.HashMap<>(payload));
        return t;
    }

    /** 已连接(sim)的连接管理器 + 指定 device_type + 点位表。 */
    private static final class Harness {
        final DeviceSimRegistry registry = new DeviceSimRegistry();
        final ConnectionManager manager;

        Harness(String deviceType, List<String> fields) {
            ProtocolAdapterFactory factory = new ProtocolAdapterFactory(
                    List.of(new SimAdapterProvider(registry, new DeviceSimFactory())));
            ApplicationService appService = mock(ApplicationService.class);
            ProtocolService protoService = mock(ProtocolService.class);

            ApplicationEntity app = new ApplicationEntity();
            app.setId(APP_ID);
            app.setApp_code("SIM_" + deviceType);
            app.setApp_name(deviceType + " 仿真");
            app.setProtocol_id(PROTOCOL_ID);
            app.setDevice_type(deviceType);
            app.setHeartbeat_interval_ms(60000L);

            ProtocolEntity protocol = new ProtocolEntity();
            protocol.setId(PROTOCOL_ID);
            protocol.setProtocol_code(deviceType.toUpperCase() + "_SIM");
            protocol.setProtocol_type("sim");

            when(appService.validateForConnect(APP_ID)).thenReturn(app);
            lenient().when(protoService.getById(PROTOCOL_ID)).thenReturn(protocol);
            lenient().when(protoService.listPoints(PROTOCOL_ID)).thenReturn(points(fields));

            manager = new ConnectionManager(appService, protoService, factory, event -> { }, null,
                    4, 5, 12000L, 5, 0L);
            manager.open(APP_ID);
        }

        DeviceSim device() {
            return registry.find(APP_ID).orElseThrow();
        }

        private List<ProtocolPointEntity> points(List<String> fields) {
            List<ProtocolPointEntity> list = new ArrayList<>();
            for (String f : fields) {
                ProtocolPointEntity p = new ProtocolPointEntity();
                p.setField_name(f);
                p.setData_type("INT");
                list.add(p);
            }
            return list;
        }
    }
}
