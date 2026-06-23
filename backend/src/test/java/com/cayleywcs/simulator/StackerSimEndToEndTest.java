package com.cayleywcs.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cayleywcs.adapter.ProtocolAdapterFactory;
import com.cayleywcs.adapter.sim.SimAdapterProvider;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.ConnectionState;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 端到端（无硬件）：ConnectionManager → SimAdapter → 堆垛机仿真设备，验证连接、6.18 取放货三段握手、故障注入。
 */
class StackerSimEndToEndTest {
    private static final long APP_ID = 1L;
    private static final long PROTOCOL_ID = 200L;

    @Test
    void connectsAndRunsStackerHandshake() {
        StackerSimulatorRegistry registry = new StackerSimulatorRegistry();
        ProtocolAdapterFactory factory = new ProtocolAdapterFactory(List.of(new SimAdapterProvider(registry)));

        ApplicationService appService = mock(ApplicationService.class);
        ProtocolService protoService = mock(ProtocolService.class);
        when(appService.validateForConnect(APP_ID)).thenReturn(simApp());
        lenient().when(protoService.getById(PROTOCOL_ID)).thenReturn(simProtocol());
        lenient().when(protoService.listPoints(PROTOCOL_ID)).thenReturn(points());

        ConnectionManager manager = new ConnectionManager(appService, protoService, factory, event -> { }, null,
                4, 5, 12000L, 5, 0L);

        // 1) 建连
        var snap = manager.open(APP_ID);
        assertThat(snap.state()).isEqualTo(ConnectionState.RUNNING.name());

        // 2) 检查阶段：模式=联机自动(2)，无任务(0)
        assertThat(intOf(manager.readPoint(APP_ID, "status_Mode"))).isEqualTo(2);
        assertThat(intOf(manager.readPoint(APP_ID, "status_Task"))).isEqualTo(0);

        // 3) 下发取/放货任务并确认执行
        manager.writePoint(APP_ID, "cmd_TaskType", 1);          // 入库
        manager.writePoint(APP_ID, "cmd_TakeCoor_Column", 3);
        manager.writePoint(APP_ID, "cmd_TakeCoor_Floor", 4);
        manager.writePoint(APP_ID, "cmd_PutCoor_Column", 5);
        manager.writePoint(APP_ID, "cmd_PutCoor_Floor", 6);
        manager.writePoint(APP_ID, "cmd_TaskNum", 1001);
        manager.writePoint(APP_ID, "cmd_ConfirmTask", 1);       // 执行任务

        assertThat(intOf(manager.readPoint(APP_ID, "status_Task"))).isEqualTo(1);              // 执行中
        assertThat(intOf(manager.readPoint(APP_ID, "status_TaskTypeFeedback"))).isEqualTo(1);
        assertThat(intOf(manager.readPoint(APP_ID, "status_CurrentColumnNum"))).isEqualTo(3);  // 取货列

        // 4) 推进设备节拍至完成
        StackerDeviceState device = registry.find(APP_ID).orElseThrow();
        device.tick();
        device.tick();
        assertThat(intOf(manager.readPoint(APP_ID, "status_Task"))).isEqualTo(2);              // 完成
        assertThat(intOf(manager.readPoint(APP_ID, "status_CurrentColumnNum"))).isEqualTo(5);  // 放货列

        // 5) 完成确认 → 回到无任务
        manager.writePoint(APP_ID, "cmd_ConfirmTask", 2);
        assertThat(intOf(manager.readPoint(APP_ID, "status_Task"))).isEqualTo(0);

        // 6) 故障注入 → 读到故障码
        device.injectFault(8);
        assertThat(intOf(manager.readPoint(APP_ID, "status_ErrorCode"))).isEqualTo(8);

        manager.shutdown();
    }

    private static int intOf(Object value) {
        return ((Number) value).intValue();
    }

    private ApplicationEntity simApp() {
        ApplicationEntity app = new ApplicationEntity();
        app.setId(APP_ID);
        app.setApp_code("SIM01");
        app.setApp_name("堆垛机仿真");
        app.setProtocol_id(PROTOCOL_ID);
        app.setHeartbeat_interval_ms(60000L); // 大间隔，避免 worker 自动 tick 干扰断言
        return app;
    }

    private ProtocolEntity simProtocol() {
        ProtocolEntity p = new ProtocolEntity();
        p.setId(PROTOCOL_ID);
        p.setProtocol_code("STACKER_SIM");
        p.setProtocol_type("sim");
        return p;
    }

    private List<ProtocolPointEntity> points() {
        List<ProtocolPointEntity> list = new ArrayList<>();
        for (String f : new String[]{
                "WCS_Heart", "cmd_TaskType", "cmd_TakeCoor_Column", "cmd_TakeCoor_Floor",
                "cmd_PutCoor_Column", "cmd_PutCoor_Floor", "cmd_TaskNum", "cmd_ConfirmTask",
                "PLC_Heart", "status_Mode", "status_Task", "status_TaskTypeFeedback",
                "status_CurrentColumnNum", "status_CurrentFloorNum", "status_ErrorCode"}) {
            ProtocolPointEntity p = new ProtocolPointEntity();
            p.setField_name(f);
            p.setData_type("INT");
            p.setRw(f.startsWith("cmd_") || f.equals("WCS_Heart") ? "W" : "R");
            list.add(p);
        }
        return list;
    }
}
