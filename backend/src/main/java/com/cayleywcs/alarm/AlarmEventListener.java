package com.cayleywcs.alarm;

import com.cayleywcs.connection.event.ConnectionStateChangedEvent;
import com.cayleywcs.connection.event.FaultDetectedEvent;
import com.cayleywcs.faultcode.FaultCodeResolver;
import com.cayleywcs.faultcode.FaultInfo;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 报警联动（需求 6 运行时）：设备故障码 → 查故障表 → 产生报警（未维护走统一兜底）；连接断开/失败 → 通讯报警；恢复 → 清除。
 */
@Component
public class AlarmEventListener {
    private final AlarmService alarmService;
    private final FaultCodeResolver faultCodeResolver;

    public AlarmEventListener(AlarmService alarmService, FaultCodeResolver faultCodeResolver) {
        this.alarmService = alarmService;
        this.faultCodeResolver = faultCodeResolver;
    }

    @EventListener
    public void onFault(FaultDetectedEvent event) {
        if (event.code() == 0) {
            alarmService.clearActiveFaults(event.appId());
            return;
        }
        FaultInfo info = faultCodeResolver.resolve(event.protocolId(), event.code());
        alarmService.raise(event.appId(), event.code(), info.level(),
                info.name() + "：" + info.message(), info.suggestion());
    }

    @EventListener
    public void onConnectionState(ConnectionStateChangedEvent event) {
        switch (event.to()) {
            case DISCONNECTED, FAILED ->
                    alarmService.raise(event.appId(), -1L, "error", "连接断开：" + event.detail(), "检查网络与设备");
            case RUNNING -> alarmService.clearCommAlarms(event.appId());
            default -> {
            }
        }
    }
}
