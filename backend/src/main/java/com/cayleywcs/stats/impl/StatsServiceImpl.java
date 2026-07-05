package com.cayleywcs.stats.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.alarm.mapper.AlarmMapper;
import com.cayleywcs.audit.entity.ConnectionLogEntity;
import com.cayleywcs.audit.mapper.ConnectionLogMapper;
import com.cayleywcs.common.support.ReconcileSupport;
import com.cayleywcs.stats.StatsService;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StatsServiceImpl implements StatsService {
    private final TaskMapper taskMapper;
    private final AlarmMapper alarmMapper;
    private final ConnectionLogMapper connectionLogMapper;

    public StatsServiceImpl(TaskMapper taskMapper, AlarmMapper alarmMapper,
                            ConnectionLogMapper connectionLogMapper) {
        this.taskMapper = taskMapper;
        this.alarmMapper = alarmMapper;
        this.connectionLogMapper = connectionLogMapper;
    }

    @Override
    public TaskSummary taskSummary(Long appId, long fromMillis, long toMillis) {
        QueryWrapper<TaskEntity> wrapper = new QueryWrapper<TaskEntity>().eq("\"is_valid\"", true);
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        if (fromMillis > 0) {
            wrapper.ge("\"create_time\"", ReconcileSupport.toLocalDateTime(fromMillis));
        }
        if (toMillis > 0) {
            wrapper.le("\"create_time\"", ReconcileSupport.toLocalDateTime(toMillis));
        }
        List<TaskEntity> tasks = taskMapper.selectList(wrapper);

        long completed = 0;
        long failed = 0;
        long cancelled = 0;
        long inFlight = 0;
        long pending = 0;
        long execSum = 0;
        long execCount = 0;
        Map<Long, Long> failCodes = new LinkedHashMap<>();
        for (TaskEntity t : tasks) {
            switch (t.getStatus() == null ? "" : t.getStatus()) {
                case TaskStatus.COMPLETED -> {
                    completed++;
                    if (t.getDispatch_time() != null && t.getFinish_time() != null) {
                        execSum += Duration.between(t.getDispatch_time(), t.getFinish_time()).toMillis();
                        execCount++;
                    }
                }
                case TaskStatus.FAILED -> {
                    failed++;
                    long code = t.getError_code() == null ? 0 : t.getError_code();
                    failCodes.merge(code, 1L, Long::sum);
                }
                case TaskStatus.CANCELLED -> cancelled++;
                case TaskStatus.DISPATCHED, TaskStatus.EXECUTING -> inFlight++;
                default -> pending++;
            }
        }
        long terminalDecided = completed + failed; // 完成率 = completed / (completed+failed)，取消不计入
        double completionRate = terminalDecided == 0 ? 0.0 : (double) completed / terminalDecided;
        List<FailCode> failTop = failCodes.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(10)
                .map(e -> new FailCode(e.getKey(), e.getValue()))
                .toList();
        return new TaskSummary(tasks.size(), completed, failed, cancelled, inFlight, pending,
                completionRate, execCount == 0 ? 0 : execSum / execCount, failTop);
    }

    /** 窗口前初始态的回溯上限：超过该回溯仍无事件的设备视为非 RUNNING（保守低估，防全表装载）。 */
    private static final int UTILIZATION_LOOKBACK_DAYS = 7;

    @Override
    public List<DeviceUtilization> deviceUtilization(Long appId, long fromMillis, long toMillis) {
        LocalDateTime windowFrom = fromMillis > 0 ? ReconcileSupport.toLocalDateTime(fromMillis)
                : LocalDateTime.now().minusDays(1);
        LocalDateTime windowTo = toMillis > 0 ? ReconcileSupport.toLocalDateTime(toMillis) : LocalDateTime.now();
        if (!windowTo.isAfter(windowFrom)) {
            return List.of();
        }
        // 取 [windowFrom-回溯, windowTo] 事件：窗口前事件只用于确定初始态，回溯有界防长期运行下全表装载。
        QueryWrapper<ConnectionLogEntity> wrapper = new QueryWrapper<ConnectionLogEntity>()
                .eq("\"event\"", "STATE_CHANGE")
                .ge("\"create_time\"", windowFrom.minusDays(UTILIZATION_LOOKBACK_DAYS))
                .le("\"create_time\"", windowTo)
                .orderByAsc("\"app_id\"").orderByAsc("\"create_time\"").orderByAsc("\"id\"");
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        List<ConnectionLogEntity> logs = connectionLogMapper.selectList(wrapper);

        Map<Long, List<ConnectionLogEntity>> byApp = new LinkedHashMap<>();
        for (ConnectionLogEntity row : logs) {
            byApp.computeIfAbsent(row.getApp_id(), k -> new ArrayList<>()).add(row);
        }
        long windowMillis = Duration.between(windowFrom, windowTo).toMillis();
        List<DeviceUtilization> out = new ArrayList<>();
        for (Map.Entry<Long, List<ConnectionLogEntity>> e : byApp.entrySet()) {
            long runningMillis = replayRunning(e.getValue(), windowFrom, windowTo);
            String appCode = e.getValue().get(e.getValue().size() - 1).getApp_code();
            out.add(new DeviceUtilization(e.getKey(), appCode, runningMillis, windowMillis,
                    windowMillis == 0 ? 0.0 : (double) runningMillis / windowMillis));
        }
        out.sort(Comparator.comparingDouble(DeviceUtilization::utilization).reversed());
        return out;
    }

    /** 重放状态迁移：state 列为迁移后的目标态，累计处于 RUNNING 的时长（裁剪到窗口）。 */
    private long replayRunning(List<ConnectionLogEntity> events, LocalDateTime from, LocalDateTime to) {
        boolean running = false;
        LocalDateTime cursor = from;
        long runningMillis = 0;
        for (ConnectionLogEntity ev : events) {
            LocalDateTime at = ev.getCreate_time();
            if (at == null) {
                continue;
            }
            if (at.isBefore(from)) {
                running = "RUNNING".equals(ev.getState()); // 窗口前事件只决定初始态
                continue;
            }
            if (running) {
                runningMillis += Duration.between(cursor, at).toMillis();
            }
            running = "RUNNING".equals(ev.getState());
            cursor = at;
        }
        if (running) {
            runningMillis += Duration.between(cursor, to).toMillis();
        }
        return Math.max(0, runningMillis);
    }

    @Override
    public List<AlarmTop> alarmTop(Long appId, long fromMillis, long toMillis, int topN) {
        QueryWrapper<AlarmEntity> wrapper = new QueryWrapper<AlarmEntity>().eq("\"is_valid\"", true);
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        if (fromMillis > 0) {
            wrapper.ge("\"raised_time\"", ReconcileSupport.toLocalDateTime(fromMillis));
        }
        if (toMillis > 0) {
            wrapper.le("\"raised_time\"", ReconcileSupport.toLocalDateTime(toMillis));
        }
        // 显式排序（latestMessage 取每码最后一条）：PG 无 order by 不保证插入序
        wrapper.orderByAsc("\"raised_time\"").orderByAsc("\"id\"");
        List<AlarmEntity> alarms = alarmMapper.selectList(wrapper);
        Map<Long, List<AlarmEntity>> byCode = new LinkedHashMap<>();
        for (AlarmEntity a : alarms) {
            byCode.computeIfAbsent(a.getFault_code() == null ? 0L : a.getFault_code(), k -> new ArrayList<>()).add(a);
        }
        int limit = topN <= 0 ? 10 : topN;
        return byCode.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Long, List<AlarmEntity>> e) -> e.getValue().size())
                        .reversed())
                .limit(limit)
                .map(e -> {
                    AlarmEntity latest = e.getValue().get(e.getValue().size() - 1);
                    return new AlarmTop(e.getKey(), e.getValue().size(), latest.getLevel(), latest.getMessage());
                })
                .toList();
    }
}
