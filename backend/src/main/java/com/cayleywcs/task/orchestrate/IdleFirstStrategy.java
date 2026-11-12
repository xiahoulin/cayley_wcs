package com.cayleywcs.task.orchestrate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 空闲优先选机（默认）：候选中「连接 RUNNING + work_mode=normal」为可用；
 * 可用者优先选完全空闲（无未完成任务）的，都不空闲则选未完成任务数最少的（排队，链不至于饿死）；
 * 全部不可用返回 null 等待。
 */
@Component
public class IdleFirstStrategy implements DeviceSelectionStrategy {
    private final ConnectionManager connectionManager;
    private final ApplicationService applicationService;
    private final TaskMapper taskMapper;

    public IdleFirstStrategy(ConnectionManager connectionManager, ApplicationService applicationService,
                             TaskMapper taskMapper) {
        this.connectionManager = connectionManager;
        this.applicationService = applicationService;
        this.taskMapper = taskMapper;
    }

    @Override
    public String code() {
        return "idle_first";
    }

    @Override
    public Long select(List<Long> candidateAppIds, SelectionContext ctx) {
        Long best = null;
        long bestBacklog = Long.MAX_VALUE;
        for (Long appId : candidateAppIds) {
            if (appId == null || !isUsable(appId)) {
                continue;
            }
            long backlog = backlog(appId);
            if (backlog == 0) {
                return appId; // 完全空闲，直接绑定
            }
            if (backlog < bestBacklog) {
                bestBacklog = backlog;
                best = appId;
            }
        }
        return best; // 无空闲则最少积压；全不可用为 null
    }

    private boolean isUsable(Long appId) {
        if (!connectionManager.isOpen(appId)
                || !"RUNNING".equals(connectionManager.snapshot(appId).state())) {
            return false;
        }
        try {
            ApplicationEntity app = applicationService.getById(appId);
            if ("agv".equals(app.getDevice_type()) || com.cayleywcs.telemetry.profile.SensorPointCatalog.isSensor(app.getDevice_type())) return false;
            String mode = app.getWork_mode();
            return mode == null || mode.isBlank() || "normal".equals(mode);
        } catch (RuntimeException ex) {
            return false; // 应用不存在/已删
        }
    }

    private long backlog(Long appId) {
        Long n = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .in("\"status\"", TaskStatus.PENDING, TaskStatus.DISPATCHED, TaskStatus.EXECUTING));
        return n == null ? 0 : n;
    }
}
