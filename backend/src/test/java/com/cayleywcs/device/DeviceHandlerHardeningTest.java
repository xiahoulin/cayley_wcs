package com.cayleywcs.device;

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
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 设备处理器回归约束：
 * ① claim 失败还原 version——防 MP 拦截器就地 +1 后引擎兜底 save 复活已取消任务；
 * ② payload 必填参数缺失/非法 → 判失败，不用 0 号站/0 层/默认开门命令设备；
 * ③ 自动门过渡态不计死等（慢门不误杀），停在错误终态才判失败；
 * ④ 仿真 reset 原地复位保持实例身份（不脱钩已连接适配器）。
 */
class DeviceHandlerHardeningTest {
    private static final long APP_ID = 1L;
    private static final long PROTOCOL_ID = 200L;

    private static final List<String> CONVEYOR_FIELDS = List.of(
            "WCS_Heart", "cmd_DestStation", "cmd_TaskNum", "cmd_Start", "cmd_ResetFault",
            "PLC_Heart", "status_Mode", "status_Task", "status_TaskNum", "status_Position",
            "status_Occupied", "status_ErrorCode");
    private static final List<String> LIFT_FIELDS = List.of(
            "WCS_Heart", "cmd_TargetFloor", "cmd_TaskNum", "cmd_Go", "cmd_ResetFault",
            "PLC_Heart", "status_Mode", "status_Task", "status_TaskNum", "status_CurrentFloor",
            "status_DoorState", "status_ErrorCode");
    private static final List<String> DOOR_FIELDS = List.of(
            "WCS_Heart", "cmd_Open", "cmd_Close", "cmd_ResetFault",
            "PLC_Heart", "status_Mode", "status_DoorState", "status_ErrorCode");

    @Test
    void claimFailureRestoresVersionAndWritesNothing() {
        Harness h = new Harness("conveyor", CONVEYOR_FIELDS);
        ConveyorDeviceHandler handler = new ConveyorDeviceHandler(h.manager);
        // 模拟 MyBatis-Plus 乐观锁拦截器：updateById 无论命中几行都把实体 version 就地 +1；此处返回 false=冲突
        TaskService persister = mock(TaskService.class);
        when(persister.save(any())).thenAnswer(inv -> {
            TaskEntity t = inv.getArgument(0);
            t.setVersion((t.getVersion() == null ? 0 : t.getVersion()) + 1);
            return false;
        });
        handler.setTaskPersister(persister);

        TaskEntity task = task("5001", Map.of("cmd_DestStation", 7));
        task.setVersion(5L);

        handler.advance(task); // CREATED -> CHECKING
        handler.advance(task); // CHECKING -> startMove：claim 失败

        assertThat(task.getVersion()).as("claim 失败须还原 version，令引擎兜底 save 也 0 行").isEqualTo(5L);
        assertThat(((Number) h.device().get("cmd_Start")).intValue()).as("占位失败不得启动设备").isEqualTo(0);
        assertThat(((Number) h.device().get("status_Task")).intValue()).isEqualTo(0);
        h.manager.shutdown();
    }

    @Test
    void conveyorMissingToStationFails() {
        Harness h = new Harness("conveyor", CONVEYOR_FIELDS);
        ConveyorDeviceHandler handler = new ConveyorDeviceHandler(h.manager);
        TaskEntity task = task("5003", Map.of()); // 无 toStation

        handler.advance(task); // CHECKING
        boolean more = handler.advance(task); // startMove → 缺参判失败

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(((Number) h.device().get("cmd_Start")).intValue()).isEqualTo(0);
        h.manager.shutdown();
    }

    @Test
    void liftMissingTargetFloorFails() {
        Harness h = new Harness("lift", LIFT_FIELDS);
        LiftDeviceHandler handler = new LiftDeviceHandler(h.manager);
        TaskEntity task = task("6003", Map.of());

        handler.advance(task); // CHECKING
        boolean more = handler.advance(task); // startMove → 缺参判失败

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        h.manager.shutdown();
    }

    @Test
    void autoDoorInvalidActionFails() {
        Harness h = new Harness("auto_door", DOOR_FIELDS);
        AutoDoorDeviceHandler handler = new AutoDoorDeviceHandler(h.manager);
        TaskEntity task = task("7003", Map.of("cmd_Open",0,"cmd_Close",0));

        boolean more = handler.advance(task); // command → action 非法判失败

        assertThat(more).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(((Number) h.device().get("cmd_Open")).intValue()).isEqualTo(0);
        h.manager.shutdown();
    }

    @Test
    void autoDoorMovingIsNotKilledButStuckTerminalFails() {
        // 慢门：过渡态(开门中)多轮不到位，不得误判失败
        Harness h = new Harness("auto_door", DOOR_FIELDS);
        AutoDoorDeviceHandler handler = new AutoDoorDeviceHandler(h.manager);
        TaskEntity task = task("7004", Map.of("cmd_Open",1,"cmd_Close",0));

        handler.advance(task); // COMMANDING（写开门 → status_DoorState=2 开门中）
        for (int i = 0; i < 10; i++) {
            boolean more = handler.advance(task); // 不 tick：门一直在“开门中”
            assertThat(more).as("过渡态不得被误判失败").isTrue();
        }
        assertThat(task.getStatus()).isEqualTo(TaskStatus.EXECUTING);
        h.device().tick(); // 开到位
        assertThat(handler.advance(task)).isFalse();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        h.manager.shutdown();

        // 命令丢失：门停在错误终态(关)，有界重试后判失败
        Harness h2 = new Harness("auto_door", DOOR_FIELDS);
        AutoDoorDeviceHandler handler2 = new AutoDoorDeviceHandler(h2.manager);
        TaskEntity task2 = task("7005", Map.of("cmd_Open",1,"cmd_Close",0));
        handler2.advance(task2);                        // COMMANDING
        h2.device().set("status_DoorState", 0);          // 模拟命令未生效/被复位，停在关
        boolean failed = false;
        for (int i = 0; i < 5 && !failed; i++) {
            failed = !handler2.advance(task2);
        }
        assertThat(task2.getStatus()).isEqualTo(TaskStatus.FAILED);
        h2.manager.shutdown();
    }

    @Test
    void faultResetReturnsDeviceToIdleSoNextTaskRuns() {
        Harness h = new Harness("conveyor", CONVEYOR_FIELDS);
        ConveyorDeviceHandler handler = new ConveyorDeviceHandler(h.manager);
        TaskEntity task = task("5010", Map.of("cmd_DestStation", 6));

        handler.advance(task); // CHECKING
        handler.advance(task); // MOVING（设备启动 → status_Task=1）
        h.device().injectFault(2);
        handler.advance(task); // 故障判失败
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(((Number) h.device().get("status_Task")).intValue()).as("故障后设备仍非空闲").isEqualTo(1);

        // 故障复位（M8 电平脉冲）→ 设备回空闲，解除对后续任务的阻塞
        h.manager.resetFault(APP_ID);
        assertThat(((Number) h.device().get("status_Task")).intValue()).isEqualTo(0);
        assertThat(((Number) h.device().get("status_ErrorCode")).intValue()).isEqualTo(0);

        // 后续任务可正常跑完
        TaskEntity next = task("5011", Map.of("cmd_DestStation", 4));
        handler.advance(next); // CHECKING（status_Task==0 通过）
        handler.advance(next); // MOVING
        h.device().tick();
        h.device().tick();
        next.setResult(Map.of("quality","good","payload",h.device().snapshot()));
        assertThat(handler.advance(next)).isTrue(); // 完成反馈先落清理阶段
        assertThat(handler.advance(next)).isFalse();
        assertThat(next.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        h.manager.shutdown();
    }

    @Test
    void deviceResetIsInPlaceKeepingIdentity() {
        Harness h = new Harness("conveyor", CONVEYOR_FIELDS);
        DeviceSim before = h.device();
        before.injectFault(2);
        before.set("cmd_Start", 1); // 让设备进运行态
        assertThat(((Number) before.get("status_ErrorCode")).intValue()).isEqualTo(2);

        before.reset();

        DeviceSim after = h.device();
        assertThat(after).as("reset 保持同一实例").isSameAs(before);
        assertThat(((Number) after.get("status_ErrorCode")).intValue()).isEqualTo(0);
        assertThat(((Number) after.get("status_Task")).intValue()).isEqualTo(0);
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
        t.setPayload(new HashMap<>(payload));
        return t;
    }

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
            app.setApp_name(deviceType);
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
