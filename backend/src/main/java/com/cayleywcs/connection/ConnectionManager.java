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
import com.cayleywcs.connection.event.FaultClearedEvent;
import com.cayleywcs.connection.event.FaultDetectedEvent;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.protocol.contract.PointContractValidator;
import com.cayleywcs.task.TaskExecutionService;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.context.annotation.Lazy;
import jakarta.annotation.PreDestroy;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 连接治理：线程池管理应用连接；最大连接数用信号量限流，满则拒绝并提示等待；
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
    private final long resetPulseHoldMs;
    private final long reconnectRearmCooldownMs;

    private final Semaphore slots;
    private final ExecutorService connectPool;
    private final ScheduledExecutorService workerPool;
    private final Map<Long, ManagedConnection> connections = new ConcurrentHashMap<>();
    /** 正在建连的 appId：串行化同一应用的并发 open（恢复器启动线程 vs 周期对账 vs 用户请求），防双 open 泄槽/孤儿 worker。 */
    private final Set<Long> opening = ConcurrentHashMap.newKeySet();
    private ProtocolContractService contracts;
    private DeviceExecutionCoordinator coordinator;
    private TaskExecutionService execution;

    @Autowired
    void setContracts(ProtocolContractService contracts,DeviceExecutionCoordinator coordinator,@Lazy TaskExecutionService execution) {
        this.contracts=contracts; this.coordinator=coordinator; this.execution=execution;
    }

    /** 测试便捷构造（省略 rearm 冷却，默认 60s）：保留既有测试的 10 参调用零改动。 */
    public ConnectionManager(ApplicationService applicationService, ProtocolService protocolService,
                             ProtocolAdapterFactory adapterFactory, ApplicationEventPublisher eventPublisher,
                             AuditService auditService, int maxConnections, int connectTimeoutSeconds,
                             long heartbeatStaleMs, int reconnectMaxRetry, long resetPulseHoldMs) {
        this(applicationService, protocolService, adapterFactory, eventPublisher, auditService,
                maxConnections, connectTimeoutSeconds, heartbeatStaleMs, reconnectMaxRetry, resetPulseHoldMs, 60000L);
    }

    @Autowired
    public ConnectionManager(ApplicationService applicationService,
                             ProtocolService protocolService,
                             ProtocolAdapterFactory adapterFactory,
                             ApplicationEventPublisher eventPublisher,
                             AuditService auditService,
                             @Value("${cayleywcs.connection.max-connections:32}") int maxConnections,
                             @Value("${cayleywcs.connection.connect-timeout-seconds:60}") int connectTimeoutSeconds,
                             @Value("${cayleywcs.connection.heartbeat-stale-ms:12000}") long heartbeatStaleMs,
                             @Value("${cayleywcs.connection.reconnect-max-retry:5}") int reconnectMaxRetry,
                             @Value("${cayleywcs.connection.reset-pulse-hold-ms:500}") long resetPulseHoldMs,
                             @Value("${cayleywcs.connection.reconnect-rearm-cooldown-ms:60000}") long reconnectRearmCooldownMs) {
        this.applicationService = applicationService;
        this.protocolService = protocolService;
        this.adapterFactory = adapterFactory;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.maxConnections = Math.max(1, maxConnections);
        this.connectTimeoutSeconds = Math.max(1, connectTimeoutSeconds);
        this.heartbeatStaleMs = Math.max(1000, heartbeatStaleMs);
        this.reconnectMaxRetry = Math.max(0, reconnectMaxRetry);
        this.resetPulseHoldMs = Math.max(0, resetPulseHoldMs);
        this.reconnectRearmCooldownMs = Math.max(0, reconnectRearmCooldownMs);
        this.slots = new Semaphore(this.maxConnections);
        this.connectPool = Executors.newFixedThreadPool(this.maxConnections, namedFactory("wcs-connect-"));
        this.workerPool = Executors.newScheduledThreadPool(this.maxConnections, namedFactory("wcs-worker-"));
    }

    /** 建立连接。 */
    public ConnectionSnapshot open(Long appId) {
        if (connections.containsKey(appId)) {
            throw new WcsException(ErrorCode.CONN_ALREADY_OPEN, "连接已存在: appId=" + appId);
        }
        // 出站开闸：校验 app_key/启用状态
        ApplicationEntity app = applicationService.validateForConnect(appId);
        ProtocolEntity protocol = protocolService.getById(app.getProtocol_id());
        List<ProtocolPointEntity> points;
        if (contracts != null) {
            if (!"active".equals(app.getConfiguration_state())) throw new WcsException(ErrorCode.CONFLICT,"应用配置正在切换");
            var revision=contracts.resolve(app);
            app.setProtocol_revision_id(revision.id());
            points=revision.points();
        } else points=protocolService.listPoints(protocol.getId());

        // 期望态=用户意图（M12）：校验通过即先落 open——即便随后建连失败，恢复器/看门狗 rearm 会持续追赶意图。
        // Opening must commit its intent before creating a transport. Deletion holds the same DB guard.
        applicationService.updateDesiredState(appId, "open");

        // 并发 open 串行化：同一 appId 只允许一个 open 进临界区，防双 open 各自 acquire 槽/put/起 worker 泄漏。
        if (!opening.add(appId)) {
            throw new WcsException(ErrorCode.CONN_ALREADY_OPEN, "连接正在建立中: appId=" + appId);
        }
        boolean ok = false;
        try {
            if (connections.containsKey(appId)) { // 二次检查（进临界区前可能已被建成）
                throw new WcsException(ErrorCode.CONN_ALREADY_OPEN, "连接已存在: appId=" + appId);
            }
            // 最大连接数限流：满则立即拒绝，提示等待
            if (!slots.tryAcquire()) {
                throw new WcsException(ErrorCode.CONN_POOL_FULL, ErrorCode.CONN_POOL_FULL.message());
            }
            try {
                ProtocolAdapter adapter = adapterFactory.create(new AdapterContext(app, protocol, points));
                ManagedConnection mc = new ManagedConnection(app, protocol, points, adapter);
                transition(mc, ConnectionState.CONNECTING, "open");
                connectWithTimeout(mc);
                if (contracts != null && !isCurrentConfiguration(mc)) {
                    mc.adapter().disconnect();
                    throw new WcsException(ErrorCode.CONFLICT,"建连期间配置版本已改变");
                }
                // 建连期间用户可能已 close（先落 desired=closed）——上线前复核意图，防「已请求关闭的连接却 RUNNING 上线」
                // 读失败不阻断（宁可上线，实际态优先）。
                if ("closed".equals(currentDesiredState(appId))) {
                    mc.adapter().disconnect();
                    transition(mc, ConnectionState.CLOSED, "aborted: desired=closed during connect");
                    throw new WcsException(ErrorCode.CONN_NOT_FOUND, "建连期间被关闭: appId=" + appId);
                }
                transition(mc, ConnectionState.CONNECTED, "connected");
                defensiveClearResetPulse(mc); // 崩溃可能遗留 cmd_ResetFault 高电平，建连成功后防御性写 0
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
        } finally {
            opening.remove(appId);
        }
    }

    public void close(Long appId) {
        if (contracts!=null) applicationService.getById(appId);
        closeTransport(appId,true);
    }

    private void closeTransport(Long appId,boolean persistIntent) {
        // 期望态=用户意图（M12）：先落 closed，防看门狗 rearm/恢复器把刚关的连接又拉起。
        if (persistIntent) persistDesiredState(appId, "closed");
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

    /** 落库期望态（防御：单条列更新失败不影响连接操作本身，仅记日志——实际态才是硬约束）。 */
    private void persistDesiredState(Long appId, String state) {
        try {
            applicationService.updateDesiredState(appId, state);
        } catch (RuntimeException ex) {
            log.warn("落库 desired_state={} 失败(appId={}): {}", state, appId, ex.getMessage());
        }
    }

    /** 建连成功后把 cmd_ResetFault 复位到 0（若协议有该点位）：清除崩溃遗留的复位脉冲高电平。 */
    private void defensiveClearResetPulse(ManagedConnection mc) {
        ProtocolPointEntity point = findPointOrNull(mc, "cmd_ResetFault");
        if (point == null) {
            return;
        }
        try {
            mc.adapter().write(point, 0);
        } catch (RuntimeException ex) {
            log.debug("防御性复位清零跳过(appId={}): {}", mc.application().getId(), ex.getMessage());
        }
    }

    public ConnectionSnapshot reconnect(Long appId) {
        if (connections.containsKey(appId)) {
            close(appId);
        }
        return open(appId);
    }

    public List<ConnectionSnapshot> snapshots() {
        if (contracts!=null) {
            Set<Long> visible=applicationService.listAll().stream().map(ApplicationEntity::getId).collect(java.util.stream.Collectors.toSet());
            return connections.entrySet().stream().filter(entry->visible.contains(entry.getKey())).map(entry->entry.getValue().snapshot()).toList();
        }
        return connections.values().stream().map(ManagedConnection::snapshot).toList();
    }

    public ConnectionSnapshot snapshot(Long appId) {
        if (contracts!=null) applicationService.getById(appId);
        ManagedConnection mc = connections.get(appId);
        if (mc == null) {
            throw new WcsException(ErrorCode.CONN_NOT_FOUND, "连接不存在: appId=" + appId);
        }
        return mc.snapshot();
    }

    /** In-process observers of already authorized gateway configurations; never exposed directly over HTTP. */
    public ConnectionSnapshot cachedSnapshot(Long appId) {
        ManagedConnection mc = connections.get(appId);
        return mc == null ? null : mc.snapshot();
    }

    public SlotUsage slotUsage() {
        return new SlotUsage(maxConnections, maxConnections - slots.availablePermits());
    }

    public boolean isOpen(Long appId) {
        return connections.containsKey(appId);
    }

    /** 手动读取单点（读 + 报文留存），供联调与前端连接监控页。 */
    public Object readPoint(Long appId, String field) {
        if (coordinator != null) return coordinator.exclusive(appId,()->readPointLocked(appId,field));
        return readPointLocked(appId,field);
    }

    private Object readPointLocked(Long appId,String field) {
        ManagedConnection mc = require(appId);
        ensureCurrent(mc);
        ProtocolPointEntity point = findPoint(mc, field);
        Object raw = mc.adapter().read(point);
        Object value;
        try { value = DataCodec.decode(mc.protocol().getProtocol_type(),point.getData_type(),raw); }
        catch (IllegalArgumentException ex) { throw new WcsException(4225,"点位反馈类型无效："+field+"，"+ex.getMessage()); }
        if (auditService != null) {
            auditService.writeMessageLog(appId, "read", field, point.getAddress(), String.valueOf(value), "{}");
        }
        return value;
    }

    /** 手动写入单点（写 + 报文留存）。任务握手(M4)也复用此写路径。 */
    public void writePoint(Long appId, String field, Object value) {
        if (coordinator != null) {
            coordinator.exclusive(appId,()->{writePointLocked(appId,field,value);return null;});
        } else writePointLocked(appId,field,value);
    }

    private void writePointLocked(Long appId,String field,Object value) {
        ManagedConnection mc = require(appId);
        ensureCurrent(mc);
        ProtocolPointEntity point = findPoint(mc, field);
        if (execution != null && coordinator.currentTask()!=null) execution.assertWriteAllowed(coordinator.currentTask());
        mc.adapter().write(point, value);
        if (auditService != null) {
            auditService.writeMessageLog(appId, "write", field, point.getAddress(), String.valueOf(value), "{}");
        }
    }

    /** Dedicated single-attempt navigation; generic point writes remain blocked for AGVs. */
    public <T> T withSeerNavigation(Long appId,java.util.function.Function<com.cayleywcs.adapter.seer.SeerTcpAdapter,T> operation) {
        return coordinator.exclusive(appId,()->{
            var mc=require(appId);ensureCurrent(mc);
            if(mc.state()!=ConnectionState.RUNNING || !(mc.adapter() instanceof com.cayleywcs.adapter.seer.SeerTcpAdapter seer))
                throw new WcsException(409,"仙工 AGV 连接未就绪");
            return operation.apply(seer);
        });
    }

    public boolean matchesConfiguration(ApplicationEntity app) {
        ManagedConnection mc=connections.get(app.getId());
        return mc != null && isCurrentConfiguration(mc)
                && Objects.equals(mc.application().getConfiguration_epoch(),app.getConfiguration_epoch());
    }

    private boolean isCurrentConfiguration(ManagedConnection mc) {
        if (contracts==null) return true;
        ApplicationEntity app=applicationService.getById(mc.application().getId());
        return "active".equals(app.getConfiguration_state())
                && Objects.equals(app.getConfiguration_epoch(),mc.application().getConfiguration_epoch())
                && Objects.equals(contracts.resolve(app).id(),mc.application().getProtocol_revision_id());
    }

    private void ensureCurrent(ManagedConnection mc) {
        if (!isCurrentConfiguration(mc)) throw new WcsException(ErrorCode.CONFLICT,"旧连接配置已失效");
    }

    public Map<String,Object> responsePayload(Long appId) {
        ManagedConnection mc=require(appId);
        Map<String,Object> response=new LinkedHashMap<>();
        for (ProtocolPointEntity point:mc.points()) {
            if (contracts==null || ("response".equals(point.getDirection()) && "business".equals(point.getPoint_scope())))
                response.put(point.getField_name(),readPoint(appId,point.getField_name()));
        }
        return response;
    }

    public Map<String,Object> validatedResponsePayload(Long appId) {
        return coordinator.exclusive(appId,()->{
            ManagedConnection mc=require(appId); ensureCurrent(mc);
            return contracts.validateResponse(mc.application(),responsePayload(appId));
        });
    }

    public Map<String,Object> readPayload(Long appId,Map<String,Object> selector) {
        applicationService.getById(appId);
        if (selector==null || selector.isEmpty()) throw new WcsException(ErrorCode.BAD_REQUEST,"payload 不能为空");
        for (var value:selector.values()) if (value!=null) throw new WcsException(ErrorCode.BAD_REQUEST,"读取点位选择值必须为null");
        return coordinator.exclusive(appId,()->{
            ManagedConnection mc=require(appId); ensureCurrent(mc);
            // 先检查全部选择项，业务读取只采集响应点位；内部驱动仍可直接读回请求握手点。
            for (String field:selector.keySet()) {
                ProtocolPointEntity point=findPoint(mc,field);
                if (!"business".equals(point.getPoint_scope()) || !"response".equals(point.getDirection()))
                    throw new WcsException(ErrorCode.FORBIDDEN,"读取点位须为对外业务响应点位："+field);
            }
            Map<String,Object> values=new LinkedHashMap<>();
            for (String field:selector.keySet()) values.put(field,readPointLocked(appId,field));
            return packet(values);
        });
    }

    public Map<String,Object> writePayload(Long appId,Map<String,Object> values) {
        if (values==null || values.isEmpty()) throw new WcsException(ErrorCode.BAD_REQUEST,"payload 不能为空");
        return coordinator.exclusive(appId,()->{
            ManagedConnection mc=require(appId); ensureCurrent(mc);
            ApplicationEntity app=applicationService.getById(appId);
            if ("agv".equals(app.getDevice_type()) || com.cayleywcs.telemetry.profile.SensorPointCatalog.isSensor(app.getDevice_type()))
                throw new WcsException(ErrorCode.FORBIDDEN,"该应用仅提供只读状态，禁止设置点位");
            if (!"maintenance".equals(app.getWork_mode()))
                throw new WcsException(ErrorCode.CONFLICT,"手动点位写入须先将设备切换到维护模式");
            execution.assertMaintenanceIdle(app);
            Map<String,Object> normalized=new LinkedHashMap<>();
            for (var entry:values.entrySet()) {
                ProtocolPointEntity point=findPoint(mc,entry.getKey());
                if (!"business".equals(point.getPoint_scope()) || !"request".equals(point.getDirection()))
                    throw new WcsException(ErrorCode.FORBIDDEN,"内部控制点须使用专用运维操作");
                normalized.put(entry.getKey(),PointContractValidator.value(mc.protocol().getProtocol_type(),point,entry.getValue(),4241));
            }
            if ("auto_door".equals(app.getDevice_type())) {
                normalized=contracts.validatePayload(app,values);
                // 先撤销另一方向，再发动作信号，避免短暂同时开门和关门。
                for (var entry:normalized.entrySet()) if (((Number)entry.getValue()).longValue()==0) writePointLocked(appId,entry.getKey(),entry.getValue());
                for (var entry:normalized.entrySet()) if (((Number)entry.getValue()).longValue()!=0) writePointLocked(appId,entry.getKey(),entry.getValue());
                return packet(normalized);
            }
            for (var entry:normalized.entrySet()) writePointLocked(appId,entry.getKey(),entry.getValue());
            return packet(normalized);
        });
    }

    private static Map<String,Object> packet(Map<String,Object> payload) {
        return Map.of("payload",payload,"quality","good","capturedAt",System.currentTimeMillis());
    }

    /**
     * 故障复位：写 cmd_ResetFault=1 → 保持一个 PLC 扫描周期 → 写 0（电平脉冲），随后清本地故障码跟踪。
     * 保持时长 reset-pulse-hold-ms(默认 500ms) 确保真实 PLC 能扫到上升沿——背靠背 1→0 会因脉冲过窄被慢扫描 PLC 漏掉。
     * 无 cmd_ResetFault 点位的协议跳过设备写（不抛异常，WCS 侧报警清除由调用方保证）。
     */
    public void resetFault(Long appId) {
        if (coordinator!=null) { coordinator.exclusive(appId,()->{resetFaultLocked(appId);return null;}); return; }
        resetFaultLocked(appId);
    }

    private void resetFaultLocked(Long appId) {
        ApplicationEntity app=applicationService.getById(appId);
        ManagedConnection mc = require(appId);
        ensureCurrent(mc);
        if (execution!=null) execution.assertFaultResetAllowed(app);
        ProtocolPointEntity point = findPointOrNull(mc, "cmd_ResetFault");
        if (point == null) {
            log.warn("故障复位跳过：协议未配置 cmd_ResetFault 点位，appId={} app={}",
                    appId, mc.application().getApp_code());
            mc.setLastErrorCodes(Collections.emptySet());
            return;
        }
        mc.adapter().write(point, 1);
        if (auditService != null) {
            auditService.writeMessageLog(appId, "write", "cmd_ResetFault", point.getAddress(), "1", "{}");
        }
        // 保持脉冲 ≥ 一个 PLC 扫描周期再撤销触发沿（仿真器同步清零，不受此保持影响）
        sleepQuietly(resetPulseHoldMs);
        mc.adapter().write(point, 0);
        if (auditService != null) {
            auditService.writeMessageLog(appId, "write", "cmd_ResetFault", point.getAddress(), "0", "{}");
        }
        // 清除本地故障码跟踪（PLC 侧复位后下轮轮询确认）
        mc.setLastErrorCodes(Collections.emptySet());
        if (execution!=null) execution.confirmFaultReset(app);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 该应用当前是否有活动故障码。供握手状态机快速判定。 */
    public boolean hasActiveFault(Long appId) {
        ManagedConnection mc = connections.get(appId);
        return mc != null && !mc.lastErrorCodes().isEmpty();
    }

    private ManagedConnection require(Long appId) {
        ManagedConnection mc = connections.get(appId);
        if (mc == null) {
            throw new WcsException(ErrorCode.CONN_NOT_FOUND, "连接不存在: appId=" + appId);
        }
        return mc;
    }

    private ProtocolPointEntity findPoint(ManagedConnection mc, String field) {
        ProtocolPointEntity point = mc.point(field);
        if (point == null) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "点位不存在: " + field);
        }
        return point;
    }

    /** 同 findPoint，但无该点位时返回 null（用于可选命令点，如 cmd_ResetFault）。 */
    private ProtocolPointEntity findPointOrNull(ManagedConnection mc, String field) {
        return mc.point(field);
    }

    // ===== 看门狗调用：检测心跳失活并自动重连；FAILED 且期望 open 则冷却后 rearm（M12 自愈）=====
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
            } else if (mc.state() == ConnectionState.FAILED) {
                rearmOrReleaseFailed(mc, now);
            }
        }
    }

    /**
     * 重连躺平修复（M12/F4）：FAILED 连接不再永久躺平——
     * - 期望态=open 且距上次失败超冷却期 → retryCount 清零回 DISCONNECTED，重入重连循环；
     * - 期望态≠open（用户已 close 或从未打算 open）→ 清理连接释放槽位（防 FAILED 连接永久占槽）。
     */
    private void rearmOrReleaseFailed(ManagedConnection mc, long now) {
        Long appId = mc.application().getId();
        String desired = currentDesiredState(appId);
        if ("open".equals(desired)) {
            if (now - mc.lastFailedAt() >= reconnectRearmCooldownMs) {
                mc.retryCount().set(0);
                transition(mc, ConnectionState.DISCONNECTED, "rearm after cooldown");
                log.info("connection {} rearm：期望 open，冷却到点重入重连", mc.application().getApp_code());
            }
        } else if (!"closed".equals(desired)) {
            // 期望态不确定（DB 抖动或查不到）：本 tick 什么都不做——绝不在信息缺失时执行
            // 不可逆的拆除。DB 恢复后下轮按真实 desired 处理；desired 真为 open 时自愈能力不丢。
            log.debug("connection {} 期望态未知，本轮 rearm 判定跳过", mc.application().getApp_code());
        } else {
            // 用户意图明确为 closed：拆除并释放槽位。
            // 条件移除（原子）：仅当本调用真正把该实例从表中摘除才 release——防与并发用户 close(appId)
            // 双重 slots.release() 导致信号量超额、击穿最大连接数（HTTP 线程 close 与看门狗线程并发）。
            if (connections.remove(appId, mc)) {
                stopWorker(mc);
                try {
                    mc.adapter().disconnect();
                } catch (RuntimeException ignored) {
                    // 忽略
                }
                transition(mc, ConnectionState.CLOSED, "released (desired!=open)");
                slots.release();
            }
        }
    }

    /**
     * 读当前期望态（仅 FAILED 连接的 rearm 判定用，频次低）。异常/查不到返回 null=「未知」——
     * 调用方对未知既不 rearm 也不 release（不在信息缺失时做不可逆拆除）。
     */
    private String currentDesiredState(Long appId) {
        try {
            ApplicationEntity app = applicationService.getById(appId);
            return app == null ? null : app.getDesired_state();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void attemptReconnect(ManagedConnection mc) {
        if (mc.retryCount().get() >= reconnectMaxRetry) {
            mc.markFailed(System.currentTimeMillis());
            transition(mc, ConnectionState.FAILED, "reconnect retries exhausted");
            return;
        }
        int attempt = mc.retryCount().incrementAndGet();
        transition(mc, ConnectionState.RECONNECTING, "reconnect attempt " + attempt);
        try {
            mc.adapter().disconnect();
            connectWithTimeout(mc);
            defensiveClearResetPulse(mc);
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
            ensureCurrent(mc);
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
        if (coordinator!=null) coordinator.exclusive(mc.application().getId(),()->{pollOnceLocked(mc);return null;});
        else pollOnceLocked(mc);
    }

    private void pollOnceLocked(ManagedConnection mc) {
        // 只监控健康连接：FAILED/RECONNECTING/DISCONNECTED/CLOSED 的生命周期由看门狗独占。
        // 否则 FAILED 连接的 worker 会把它打回 DISCONNECTED → 看门狗 attemptReconnect 耗尽路径每 tick markFailed(now)
        // 刷新 lastFailedAt → rearm 的「距上次失败超冷却」永不成立 → 躺平修复被永久饿死（且状态/报警每 5s 横跳一次）。
        // 顺带消除拆除中连接被 pollOnce 回写 RUNNING 的幽灵事件。
        ConnectionState st = mc.state();
        if (st != ConnectionState.RUNNING && st != ConnectionState.CONNECTED) {
            return;
        }
        try {
            ensureCurrent(mc);
            if (!mc.adapter().isConnected()) {
                transition(mc, ConnectionState.DISCONNECTED, "adapter not connected");
                return;
            }
            mc.adapter().heartbeat();
            Map<String, Object> snap = mc.adapter().readAll(mc.points());
            ensureCurrent(mc);
            if (contracts!=null) {
                Map<String,Object> business=new LinkedHashMap<>();
                for (var point:mc.points()) if ("business".equals(point.getPoint_scope()) && "response".equals(point.getDirection()))
                    business.put(point.getField_name(),snap.get(point.getField_name()));
                Map<String,Object> normalized=contracts.validateResponse(mc.application(),business);
                snap=new LinkedHashMap<>(snap); snap.putAll(normalized);
            }
            mc.setLatest(snap);
            mc.markHeartbeat(System.currentTimeMillis());
            detectFaultEdge(mc, snap);
            if (mc.state() != ConnectionState.RUNNING) {
                transition(mc, ConnectionState.RUNNING, "poll ok");
            }
        } catch (Exception ex) {
            if (contracts!=null && !isCurrentConfiguration(mc)) return;
            transition(mc, ConnectionState.DISCONNECTED, "poll error: " + ex.getMessage());
        }
    }

    /** 故障码边沿检测：status_ErrorCode 变化即发布事件（报警监听在 M5 联动）。支持标量(仿真)和数组(真实PLC ARRAY[1..60])。 */
    private void detectFaultEdge(ManagedConnection mc, Map<String, Object> snap) {
        Object err = snap.get("status_ErrorCode");
        Set<Integer> currentCodes = extractFaultCodes(err);
        Set<Integer> prevCodes = mc.lastErrorCodes();

        if (!currentCodes.equals(prevCodes)) {
            mc.setLastErrorCodes(currentCodes);
            if (eventPublisher != null) {
                Long appId = mc.application().getId();
                Long protocolId = mc.protocol().getId();
                // 新增故障码 → 逐个 raise
                for (int code : currentCodes) {
                    if (!prevCodes.contains(code)) {
                        eventPublisher.publishEvent(new FaultDetectedEvent(appId, protocolId, code));
                    }
                }
                // 消失故障码 → 逐个精确解除（支持多故障部分恢复，不再等全清才清警）
                for (int code : prevCodes) {
                    if (!currentCodes.contains(code)) {
                        eventPublisher.publishEvent(new FaultClearedEvent(appId, protocolId, code));
                    }
                }
            }
        }
    }

    /** 从轮询值提取非零故障码集合。支持标量(仿真器)、List(数组解码后)、数组。 */
    private static Set<Integer> extractFaultCodes(Object err) {
        if (err == null) {
            return Collections.emptySet();
        }
        if (err instanceof Number n) {
            int code = n.intValue();
            return code == 0 ? Collections.emptySet() : Set.of(code);
        }
        if (err instanceof Collection<?> coll) {
            Set<Integer> codes = new java.util.LinkedHashSet<>();
            for (Object elem : coll) {
                int code = elem instanceof Number num ? num.intValue() : 0;
                if (code != 0) {
                    codes.add(code);
                }
            }
            return codes;
        }
        if (err instanceof Object[] arr) {
            Set<Integer> codes = new java.util.LinkedHashSet<>();
            for (Object elem : arr) {
                int code = elem instanceof Number num ? num.intValue() : 0;
                if (code != 0) {
                    codes.add(code);
                }
            }
            return codes;
        }
        return Collections.emptySet();
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
                closeTransport(id,false); // 服务退出不等于用户关闭；保留期望态供启动恢复。
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
