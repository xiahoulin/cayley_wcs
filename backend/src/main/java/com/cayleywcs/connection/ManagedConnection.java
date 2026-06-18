package com.cayleywcs.connection;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个被治理的连接：持有应用/协议/点位/适配器与运行态。每个连接一条工作线程（轮询心跳+状态）。
 */
public class ManagedConnection {
    private final ApplicationEntity application;
    private final ProtocolEntity protocol;
    private final List<ProtocolPointEntity> points;
    private final ProtocolAdapter adapter;
    private final AdapterContext context;

    private volatile ConnectionState state = ConnectionState.NEW;
    private volatile long lastHeartbeatAt = 0L;
    private volatile int lastErrorCode = 0;
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private volatile ObjectNode latest = JsonNodeFactory.instance.objectNode();
    private volatile ScheduledFuture<?> workerFuture;

    public ManagedConnection(ApplicationEntity application, ProtocolEntity protocol,
                             List<ProtocolPointEntity> points, ProtocolAdapter adapter) {
        this.application = application;
        this.protocol = protocol;
        this.points = points;
        this.adapter = adapter;
        this.context = new AdapterContext(application, protocol, points);
    }

    public ApplicationEntity application() {
        return application;
    }

    public ProtocolEntity protocol() {
        return protocol;
    }

    public List<ProtocolPointEntity> points() {
        return points;
    }

    public ProtocolAdapter adapter() {
        return adapter;
    }

    public AdapterContext context() {
        return context;
    }

    public ConnectionState state() {
        return state;
    }

    public void setState(ConnectionState state) {
        this.state = state;
    }

    public long lastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void markHeartbeat(long epochMillis) {
        this.lastHeartbeatAt = epochMillis;
    }

    public AtomicInteger retryCount() {
        return retryCount;
    }

    public int lastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(int lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public ObjectNode latest() {
        return latest;
    }

    public void setLatest(ObjectNode latest) {
        this.latest = latest;
    }

    public ScheduledFuture<?> workerFuture() {
        return workerFuture;
    }

    public void setWorkerFuture(ScheduledFuture<?> workerFuture) {
        this.workerFuture = workerFuture;
    }

    public ConnectionSnapshot snapshot() {
        return new ConnectionSnapshot(
                application.getId(),
                application.getApp_code(),
                application.getApp_name(),
                protocol.getProtocol_type(),
                state.name(),
                lastHeartbeatAt,
                retryCount.get(),
                latest
        );
    }
}
