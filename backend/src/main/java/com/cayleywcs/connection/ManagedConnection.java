package com.cayleywcs.connection;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个被治理的连接：持有应用/协议/点位/适配器与运行态。每个连接一条工作线程（轮询心跳+状态）。
 */
public class ManagedConnection {
    private final ApplicationEntity application;
    private final ProtocolEntity protocol;
    private final List<ProtocolPointEntity> points;
    private final Map<String, ProtocolPointEntity> pointsByField;
    private final ProtocolAdapter adapter;
    private final AdapterContext context;

    private volatile ConnectionState state = ConnectionState.NEW;
    private volatile long lastHeartbeatAt = 0L;
    private volatile long lastFailedAt = 0L;
    private volatile Set<Integer> lastErrorCodes = Collections.emptySet();
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private volatile Map<String, Object> latest = new LinkedHashMap<>();
    private volatile ScheduledFuture<?> workerFuture;

    public ManagedConnection(ApplicationEntity application, ProtocolEntity protocol,
                             List<ProtocolPointEntity> points, ProtocolAdapter adapter) {
        this.application = application;
        this.protocol = protocol;
        this.points = List.copyOf(points);
        Map<String, ProtocolPointEntity> firstByField = new LinkedHashMap<>();
        for (ProtocolPointEntity point : this.points) {
            if (firstByField.put(point.getField_name(), point) != null)
                throw new com.cayleywcs.common.exception.WcsException(4223,"当前设备模板存在重复点位："+point.getField_name());
        }
        this.pointsByField = Collections.unmodifiableMap(firstByField);
        this.adapter = adapter;
        this.context = new AdapterContext(application, protocol, this.points);
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

    /** 已限定设备模板/协议版本的运行索引；重复名称在建连前拒绝。 */
    public ProtocolPointEntity point(String field) {
        return pointsByField.get(field);
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

    public long lastFailedAt() {
        return lastFailedAt;
    }

    public void markFailed(long epochMillis) {
        this.lastFailedAt = epochMillis;
    }

    public AtomicInteger retryCount() {
        return retryCount;
    }

    public Set<Integer> lastErrorCodes() {
        return lastErrorCodes;
    }

    public void setLastErrorCodes(Set<Integer> lastErrorCodes) {
        this.lastErrorCodes = lastErrorCodes;
    }

    public Map<String, Object> latest() {
        return latest;
    }

    public void setLatest(Map<String, Object> latest) {
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
                application.getDevice_type(),
                protocol.getProtocol_type(),
                state.name(),
                lastHeartbeatAt,
                retryCount.get(),
                state==ConnectionState.RUNNING?latest:null
        );
    }
}
