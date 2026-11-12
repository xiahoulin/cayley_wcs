package com.cayleywcs.task;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.ConnectionSnapshot;
import com.cayleywcs.config.WcsMetrics;
import com.cayleywcs.device.DeviceHandler;
import com.cayleywcs.device.DeviceHandlerRegistry;
import com.cayleywcs.task.entity.TaskEntity;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务调度引擎：周期为每个 RUNNING 连接推进其下一个任务（设备级单飞），按 device_type 路由到 DeviceHandler。
 * 并发模型（F1 修复）：tick 只扇出，PLC I/O 全部在 worker 池执行——慢设备不阻塞全局调度线程与其他设备派工。
 */
@Component
public class TaskEngine {
    private static final Logger log = LoggerFactory.getLogger(TaskEngine.class);

    /** 配置类报警码：未知设备类型（通讯报警为 -1；fault_code<=0 的报警在连接恢复 RUNNING 时统一清除）。 */
    public static final long FAULT_CODE_UNKNOWN_DEVICE_TYPE = -2L;

    private final ConnectionManager connectionManager;
    private final TaskService taskService;
    private final DeviceHandlerRegistry handlerRegistry;
    private final AlarmService alarmService;
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();
    private final ExecutorService workerPool;
    private WcsMetrics metrics;
    private TaskExecutionService execution;
    private com.cayleywcs.connection.DeviceExecutionCoordinator coordinator;

    @Autowired
    void setExecution(TaskExecutionService execution,com.cayleywcs.connection.DeviceExecutionCoordinator coordinator) {
        this.execution=execution; this.coordinator=coordinator;
    }

    @Autowired
    void setMetrics(WcsMetrics metrics) {
        this.metrics = metrics;
        metrics.registerTaskWorker(busy);
    }

    public TaskEngine(ConnectionManager connectionManager, TaskService taskService,
                      DeviceHandlerRegistry handlerRegistry, AlarmService alarmService,
                      @Value("${cayleywcs.connection.max-connections:32}") int maxConnections) {
        this.connectionManager = connectionManager;
        this.taskService = taskService;
        this.handlerRegistry = handlerRegistry;
        this.alarmService = alarmService;
        AtomicInteger counter = new AtomicInteger();
        this.workerPool = Executors.newFixedThreadPool(Math.max(1, maxConnections), runnable -> {
            Thread thread = new Thread(runnable, "wcs-task-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${cayleywcs.task.engine-interval-ms:1000}", scheduler = "taskScheduler")
    public void tick() {
        WcsMetrics.tick(metrics, "task-engine", this::tickOnce);
    }

    private void tickOnce() {
        for (ConnectionSnapshot snap : connectionManager.snapshots()) {
            if (!"RUNNING".equals(snap.state())) {
                continue;
            }
            if (busy.contains(snap.appId())) {
                continue; // 提交前预检：上一步还没跑完就不入队，防止同 app 在池队列积压
            }
            workerPool.submit(() -> advanceApp(snap.appId(), snap.deviceType()));
        }
    }

    /** 推进某应用的下一个任务一步。设备级单飞：同一应用同时只处理一个任务一步。 */
    public void advanceApp(Long appId, String deviceType) {
        if (!busy.add(appId)) {
            return; // 任务体内互斥：与预检双保险
        }
        try {
            TaskEntity task = taskService.findNextForApp(appId);
            if (task == null) {
                return;
            }
            DeviceHandler handler = handlerRegistry.find(deviceType).orElse(null);
            if (handler == null) {
                // 未知设备类型不静默（存量脏数据兜底；新增/修改应用时已在服务层校验拒绝）：
                // 报警(去重)让停摆可见；任务保持 pending，修正 device_type 后自动恢复调度。
                alarmService.raise(appId, FAULT_CODE_UNKNOWN_DEVICE_TYPE, "error",
                        "未知设备类型：" + deviceType + "，任务 " + task.getTask_no() + " 无法调度",
                        "检查应用 device_type 配置；已注册类型：" + handlerRegistry.supportedTypes());
                log.warn("app {} 设备类型 {} 无已注册 handler，任务 {} 暂停调度", appId, deviceType, task.getTask_no());
                return;
            }
            Runnable advance=()->{
                if (execution != null && !execution.prepare(task)) return;
                handler.advance(task);
            };
            if (coordinator != null) coordinator.runTask(task,advance); else advance.run();
            if (!taskService.save(task)) {
                // 乐观锁冲突（管理端取消/改优先级等并发写赢了）：丢弃本步，下轮以 DB 最新状态重读推进。
                log.warn("task {} 保存冲突(version)，丢弃本步，下轮重读", task.getTask_no());
            } else if (execution != null) {
                execution.releaseIfCompleted(task);
            }
        } catch (RuntimeException ex) {
            log.warn("task engine error for app {}: {}", appId, ex.getMessage());
        } finally {
            busy.remove(appId);
        }
    }

    @PreDestroy
    public void shutdown() {
        // 优雅停机：先拒新任务，等在飞 advance 走完（设备写+落库原子完成）再放行销毁——
        // TaskEngine 依赖 ConnectionManager，Spring 先销毁本类；若不等待，随后的连接拆除会使在飞
        // readPoint 抛异常。异常路径已不落终态（状态机放弃本步），此处等待进一步保证设备写不被中断。
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
        }
    }
}
