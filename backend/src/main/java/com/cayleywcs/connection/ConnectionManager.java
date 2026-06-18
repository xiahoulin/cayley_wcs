package com.cayleywcs.connection;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.DataCodec;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterFactory;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.audit.AuditService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.event.ConnectionStateChangedEvent;
import com.cayleywcs.connection.event.FaultDetectedEvent;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 连接治理（需求 4）：线程池管理应用连接；最大连接数用信号量限流，满则拒绝并提示等待；
 * 每次建连有超时（默认 60s），超时未连成功则取消并回收连接槽。成功后每个连接一条工作线程做心跳+状态轮询。
 */
@Component
public class ConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final ApplicationService applicationService;
    private final ProtocolService protocolService;
    private final ProtocolAdapterFactory adapterFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    private final int maxConnections;
    private final int connectTimeoutSeconds;
    private final long heartbeatStaleMs;
    private final int reconnectMaxRetry;

    private final Semaphore slots;
    private final ExecutorService connectPool;
    private final ScheduledExecutorService workerPool;
    private final Map<Long, ManagedConnection> connections = new ConcurrentHashMap<>();

    public ConnectionManager(ApplicationService applicationService,
                             ProtocolService protocolService,
                             ProtocolAdapterFactory adapterFactory,
                             ApplicationEventPublisher eventPublisher,
                             AuditService auditService,
                             @Value("${cayleywcs.connection.max-connections:32}") int maxConnections,
                             @Value("${cayleywcs.connection.connect-timeout-seconds:60}") int connectTimeoutSeconds,
                             @Value("${cayleywcs.connection.heartbeat-stale-ms:12000}") long heartbeatStaleMs,
                             @Value("${cayleywcs.connection.reconnect-max-retry:5}") int reconnectMaxRetry) {
        this.applicationService = applicationService;
        this.protocolService = protocolService;
        this.adapterFactory = adapterFactory;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.maxConnections = Math.max(1, maxConnections);
        this.connectTimeoutSeconds = Math.max(1, connectTimeoutSeconds);
        this.heartbeatStaleMs = Math.max(1000, heartbeatStaleMs);
        this.reconnectMaxRetry = Math.max(0, reconnectMaxRetry);
        this.slots = new Semaphore(this.maxConnections);
        this.connectPool = Executors.newFixedThreadPool(this.maxConnections, namedFactory("wcs-connect-"));
        this.workerPool = Executors.newScheduledThreadPool(this.maxConnections, namedFactory("wcs-worker-"));
    }

    /** 建立连接（需求 4 全流程）。 */
    public ConnectionSnapshot open(Long appId) {
        if (connections.containsKey(appId)) {
            throw new WcsException(ErrorCode.CONN_ALREADY_OPEN, "连接已存在: appId=" + appId);
        }
        // 出站开闸：校验 app_key/启用状态
        ApplicationEntity app = applicationService.validateForConnect(appId);
        ProtocolEntity protocol = protocolService.getById(app.getProtocol_id());
        List<ProtocolPointEntity> points = protocolService.listPoints(protocol.getId());

        // 最大连接数限流：满则立即拒绝，提示等待
        if (!slots.tryAcquire()) {
            throw new WcsException(ErrorCode.CONN_POOL_FULL, ErrorCode.CONN_POOL_FULL.message());
        }

        boolean ok = false;
        try {
            ProtocolAdapter adapter = adapterFactory.create(new AdapterContext(app, protocol, points));
            ManagedConnection mc = new ManagedConnection(app, protocol, points, adapter);
            transition(mc, ConnectionState.CONNECTING, "open");
            connectWithTimeout(mc);
            transition(mc, ConnectionState.CONNECTED, "connected");
            mc.markHeartbeat(System.currentTimeMillis());
            connections.put(appId, mc);
            startWorker(mc);
            transition(mc, ConnectionState.RUNNING, "running");
            ok = true;
            return mc.snapshot();
        } finally {
            if (!ok) {
                connections.remove(appId);
                slots.release();
            }
        }
    }

    public void close(Long appId) {
        ManagedConnection mc = connections.remove(appId);
        if (mc == null) {
            throw new WcsException(ErrorCode.CONN_NOT_FOUND, "连接不存在: appId=" + appId);
        }
        stopWorker(mc);
        try {
            mc.adapter().disconnect();
        } finally {
            transition(mc, ConnectionState.CLOSED, "closed");
            slots.release();
        }
    }

    public ConnectionSnapshot reconnect(Long appId) {
        if (connections.containsKey(appId)) {
            close(appId);
        }
        return open(appId);
    }

    public List<ConnectionSnapshot> snapshots() {
        return connections.values().stream().map(ManagedConnection::snapshot).toList();
    }

    public ConnectionSnapshot snapshot(Long appId) {
        ManagedConnection mc = connections.get(appId);
        if (mc == null) {
            throw new WcsException(ErrorCode.CONN_NOT_FOUND, "连接不存在: appId=" + appId);
        }
        return mc.snapshot();
    }

    public SlotUsage slotUsage() {
        return new SlotUsage(maxConnections, maxConnections - slots.availablePermits());
    }

    public boolean isOpen(Long appId) {
        return connections.containsKey(appId);
    }

    /** 手动读取单点（读 + 报文留存），供联调与前端连接监控页。 */
    public Object readPoint(Long appId, String field) {
        ManagedConnection mc = require(appId);
        ProtocolPointEntity point = findPoint(mc, field);
        Object raw = mc.adapter().read(point);
        Object value = DataCodec.decode(point.getData_type(), raw);
        if (auditService != null) {
            auditService.writeMessageLog(appId, "read", field, point.getAddress(), String.valueOf(value), "{}");
        }
        return value;
    }

    /** 手动写入单点（写 + 报文留存）。任务握手(M4)也复用此写路径。 */
    public void writePoint(Long appId, String field, Object value) {
        ManagedConnection mc = require(appId);
        ProtocolPointEntity point = findPoint(mc, field);
        mc.adapter().write(point, value);
        if (auditService != null) {
            auditService.writeMessageLog(appId, "write", field, point.getAddress(), String.valueOf(value), "{}");
        }
    }

    private ManagedConnection require(Long appId) {
        ManagedConnection mc = connections.get(appId);
        if (mc == null) {
            throw new WcsException(ErrorCode.CONN_NOT_FOUND, "连接不存在: appId=" + appId);
        }
        return mc;
    }

    private ProtocolPointEntity findPoint(ManagedConnection mc, String field) {
        return mc.points().stream()
                .filter(p -> field.equals(p.getField_name()))
                .findFirst()
                .orElseThrow(() -> new WcsException(ErrorCode.BAD_REQUEST, "点位不存在: " + field));
    }

    // ===== 看门狗调用：检测心跳失活并自动重连 =====
    public void runWatchdogOnce() {
        long now = System.currentTimeMillis();
        for (ManagedConnection mc : connections.values()) {
            ConnectionState state = mc.state();
            if (state == ConnectionState.RUNNING || state == ConnectionState.CONNECTED) {
                if (now - mc.lastHeartbeatAt() > heartbeatStaleMs) {
                    transition(mc, ConnectionState.DISCONNECTED, "heartbeat stale");
                }
            }
            if (mc.state() == ConnectionState.DISCONNECTED) {
                attemptReconnect(mc);
            }
        }
    }

    private void attemptReconnect(ManagedConnection mc) {
        if (mc.retryCount().get() >= reconnectMaxRetry) {
            transition(mc, ConnectionState.FAILED, "reconnect retries exhausted");
            return;
        }
        int attempt = mc.retryCount().incrementAndGet();
        transition(mc, ConnectionState.RECONNECTING, "reconnect attempt " + attempt);
        try {
            mc.adapter().disconnect();
            connectWithTimeout(mc);
            mc.markHeartbeat(System.currentTimeMillis());
            mc.retryCount().set(0);
            transition(mc, ConnectionState.RUNNING, "reconnected");
        } catch (Exception ex) {
            transition(mc, ConnectionState.DISCONNECTED, "reconnect failed: " + ex.getMessage());
        }
    }

    private void connectWithTimeout(ManagedConnection mc) {
        Future<?> future = connectPool.submit(() -> {
            mc.adapter().connect(mc.context());
            return null;
        });
        try {
            future.get(connectTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            transition(mc, ConnectionState.FAILED, "connect timeout");
            throw new WcsException(ErrorCode.CONN_TIMEOUT,
                    "建立连接超时(" + connectTimeoutSeconds + "s): " + mc.application().getApp_code());
        } catch (ExecutionException ex) {
            String msg = ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage();
            transition(mc, ConnectionState.FAILED, "connect failed: " + msg);
            throw new WcsException(ErrorCode.CONN_FAILED, "建立连接失败: " + msg);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new WcsException(ErrorCode.CONN_FAILED, "建立连接被中断");
        }
    }

    private void startWorker(ManagedConnection mc) {
        long interval = mc.application().getHeartbeat_interval_ms() == null
                ? 5000L : Math.max(500L, mc.application().getHeartbeat_interval_ms());
        var future = workerPool.scheduleWithFixedDelay(() -> pollOnce(mc), interval, interval, TimeUnit.MILLISECONDS);
        mc.setWorkerFuture(future);
    }

    private void stopWorker(ManagedConnection mc) {
        if (mc.workerFuture() != null) {
            mc.workerFuture().cancel(true);
        }
    }

    private void pollOnce(ManagedConnection mc) {
        try {
            if (!mc.adapter().isConnected()) {
                transition(mc, ConnectionState.DISCONNECTED, "adapter not connected");
                return;
            }
            mc.adapter().heartbeat();
            ObjectNode snap = mc.adapter().readAll(mc.points());
            mc.setLatest(snap);
            mc.markHeartbeat(System.currentTimeMillis());
            detectFaultEdge(mc, snap);
            if (mc.state() != ConnectionState.RUNNING) {
                transition(mc, ConnectionState.RUNNING, "poll ok");
            }
        } catch (Exception ex) {
            transition(mc, ConnectionState.DISCONNECTED, "poll error: " + ex.getMessage());
        }
    }

    /** 故障码边沿检测：status_ErrorCode 变化即发布事件（报警监听在 M5 联动）。 */
    private void detectFaultEdge(ManagedConnection mc, ObjectNode snap) {
        JsonNode err = snap.get("status_ErrorCode");
        // 注：真实协议 status_ErrorCode 为 ARRAY[1..60] OF INT，此处先处理标量(仿真)；数组扫描后续补。
        int code = (err != null && err.isNumber()) ? err.asInt() : 0;
        if (code != mc.lastErrorCode()) {
            mc.setLastErrorCode(code);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new FaultDetectedEvent(
                        mc.application().getId(), mc.protocol().getId(), code));
            }
        }
    }

    private void transition(ManagedConnection mc, ConnectionState to, String detail) {
        ConnectionState from = mc.state();
        mc.setState(to);
        if (from != to) {
            log.debug("connection {} {} -> {} ({})", mc.application().getApp_code(), from, to, detail);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new ConnectionStateChangedEvent(
                        mc.application().getId(), mc.application().getApp_code(), from, to, detail));
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        connections.keySet().forEach(id -> {
            try {
                close(id);
            } catch (RuntimeException ignored) {
                // 关闭期间忽略
            }
        });
        connectPool.shutdownNow();
        workerPool.shutdownNow();
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public record SlotUsage(int max, int used) {
        public int available() {
            return max - used;
        }
    }
}
