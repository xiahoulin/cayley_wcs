package com.cayleywcs.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务恢复调度器（M11）：
 * ① 独立任务自动重试：failed && chain_id IS NULL && app.failure_policy=auto_retry && 未耗尽 && 退避到点
 *    → {@link TaskService#resetForRetry}（链子任务的重试由编排器在链级挂钩，chain_id 判空天然分工）；
 * ② 在飞任务停滞检测：dispatched/executing 且 last_update_time 停滞超 app.task_stale_seconds（0=关闭）
 *    → 判 failed + 报警(-4)。引擎每步 save 都会 touch，故停滞时长即「无进展时长」（连接断开后引擎不再推进）。
 * 重试上限：任务级 max_retry>0 优先，否则应用级 max_retry（V12 起承担任务重试默认上限语义）。
 */
@Component
public class TaskRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryScheduler.class);

    /** 运维类报警码：在飞任务停滞（-1 通讯 / -2 未知设备类型 / -3 编排 / -4 任务停滞）。 */
    public static final long FAULT_CODE_TASK_STALE = -4L;
    /** 每轮扫描上限（防大表全扫；下轮继续）。 */
    private static final int SCAN_LIMIT = 200;

    private final TaskMapper taskMapper;
    private final TaskService taskService;
    private final ApplicationService applicationService;
    private final AlarmService alarmService;
    private final long retryBackoffMs;

    public TaskRecoveryScheduler(TaskMapper taskMapper, TaskService taskService,
                                 ApplicationService applicationService, AlarmService alarmService,
                                 @Value("${cayleywcs.task.retry-backoff-ms:30000}") long retryBackoffMs) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.applicationService = applicationService;
        this.alarmService = alarmService;
        this.retryBackoffMs = retryBackoffMs;
    }

    @Scheduled(fixedDelayString = "${cayleywcs.task.recovery-interval-ms:5000}")
    public void tick() {
        runOnce();
    }

    /** 单轮扫描（公开供测试驱动）。 */
    public void runOnce() {
        // 先按应用策略收敛扫描范围（应用表小）：否则 manual 失败任务（last_update_time 永不变）
        // 会永久占据 order by lu asc 的扫描窗前列，饿死排在后面的 auto_retry 任务。
        Map<Long, ApplicationEntity> apps = applicationMapAll();
        retryFailedStandaloneTasks(apps);
        failStaleInFlightTasks(apps);
    }

    private Map<Long, ApplicationEntity> applicationMapAll() {
        return applicationService.listAll().stream()
                .collect(Collectors.toMap(ApplicationEntity::getId, a -> a, (a, b) -> a, HashMap::new));
    }

    private void retryFailedStandaloneTasks(Map<Long, ApplicationEntity> apps) {
        List<ApplicationEntity> autoRetryApps = apps.values().stream()
                .filter(a -> "auto_retry".equals(a.getFailure_policy()))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        // 逐应用扫描且**耗尽行在 SQL 层排除**（M11 审查 P2）：耗尽任务 last_update_time 永久冻结，
        // 若进入 order by lu asc 的扫描窗会累积霸占前列、饿死后面的可重试任务——必须不进结果集。
        for (ApplicationEntity app : autoRetryApps) {
            long appMax = app.getMax_retry() == null ? 0 : app.getMax_retry();
            QueryWrapper<TaskEntity> wrapper = new QueryWrapper<TaskEntity>()
                    .eq("\"is_valid\"", true)
                    .eq("\"status\"", TaskStatus.FAILED)
                    .isNull("\"chain_id\"")
                    .eq("\"app_id\"", app.getId())
                    .and(q -> {
                        q.apply("(\"max_retry\" > 0 and \"retry_count\" < \"max_retry\")");
                        if (appMax > 0) {
                            q.or().apply("(\"max_retry\" = 0 and \"retry_count\" < {0})", appMax);
                        }
                    })
                    .last("order by \"last_update_time\" asc limit " + SCAN_LIMIT);
            for (TaskEntity t : taskMapper.selectList(wrapper)) {
                if (exhausted(t, app)) {
                    continue; // 双保险（SQL 已排除）
                }
                if (t.getLast_update_time() != null
                        && Duration.between(t.getLast_update_time(), now).toMillis() < retryBackoffMs) {
                    continue; // 退避未到点（退避后 lu 不变，仍会被下轮扫到，非饥饿）
                }
                if (taskService.resetForRetry(t.getId())) {
                    log.info("task {} 自动重派（第 {} 次重试, app {}）", t.getTask_no(),
                            (t.getRetry_count() == null ? 0 : t.getRetry_count()) + 1, app.getApp_code());
                }
            }
        }
    }

    private void failStaleInFlightTasks(Map<Long, ApplicationEntity> apps) {
        List<Long> staleWatchAppIds = apps.values().stream()
                .filter(a -> a.getTask_stale_seconds() != null && a.getTask_stale_seconds() > 0)
                .map(ApplicationEntity::getId)
                .toList();
        if (staleWatchAppIds.isEmpty()) {
            return;
        }
        List<TaskEntity> inFlight = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .in("\"status\"", TaskStatus.DISPATCHED, TaskStatus.EXECUTING)
                .in("\"app_id\"", staleWatchAppIds)
                .last("order by \"last_update_time\" asc limit " + SCAN_LIMIT));
        LocalDateTime now = LocalDateTime.now();
        for (TaskEntity t : inFlight) {
            ApplicationEntity app = apps.get(t.getApp_id());
            long staleSeconds = app == null || app.getTask_stale_seconds() == null ? 0 : app.getTask_stale_seconds();
            if (staleSeconds <= 0 || t.getLast_update_time() == null
                    || Duration.between(t.getLast_update_time(), now).getSeconds() < staleSeconds) {
                continue;
            }
            // 停滞转 failed（乐观锁：引擎并发推进赢了则 0 行跳过——推进本身就刷新了 last_update_time）
            t.setStatus(TaskStatus.FAILED);
            t.setHandshake_step("FAILED");
            t.setFinish_time(now);
            Audits.touch(t);
            if (taskMapper.updateById(t) == 1) {
                alarmService.raise(t.getApp_id(), FAULT_CODE_TASK_STALE, "error",
                        "任务 " + t.getTask_no() + " 停滞超 " + staleSeconds + "s 无进展，已判失败",
                        "检查设备连接/设备状态；恢复后按 failure_policy 自动重派或人工重试");
                log.warn("task {} 停滞超时({}s)转 failed", t.getTask_no(), staleSeconds);
            }
        }
    }

    private boolean exhausted(TaskEntity t, ApplicationEntity app) {
        long taskMax = t.getMax_retry() == null ? 0 : t.getMax_retry();
        long appMax = app.getMax_retry() == null ? 0 : app.getMax_retry();
        long effectiveMax = taskMax > 0 ? taskMax : appMax;
        long count = t.getRetry_count() == null ? 0 : t.getRetry_count();
        return count >= effectiveMax;
    }

}
