package com.cayleywcs.audit;

import com.cayleywcs.connection.event.ConnectionStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 消费连接状态变化事件（此前已发布但无监听者），落库到 wcs_connection_log。
 */
@Component
public class ConnectionAuditListener {
    private final AuditService auditService;

    public ConnectionAuditListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @EventListener
    public void onStateChanged(ConnectionStateChangedEvent event) {
        auditService.writeConnectionLog(event.appId(), event.appCode(), "STATE_CHANGE",
                event.to().name(), event.from() + " -> " + event.to() + " : " + event.detail());
    }
}
