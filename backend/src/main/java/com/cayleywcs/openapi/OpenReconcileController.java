package com.cayleywcs.openapi;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.ConnectionSnapshot;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.entity.TaskEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WMS 对账闭环（开放接口，AppKey 鉴权）。
 * WS /ws/monitor 给低延迟提示（带 seq）；WMS 发现断号/重连后，用这里的接口按水位线拉权威状态：
 *  - /open/task/query  增量拉任务（sinceMillis 水位线）
 *  - /open/alarm/query 增量拉报警
 *  - /open/snapshot    断线后一把全量重同步（实时态 + 活动报警 + 在飞任务）
 * 用法：处理 rows 后按 id 去重，下次 sinceMillis 传上次响应的 serverTimeMillis。
 */
@RestController
@RequestMapping(value = "/open", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "OpenReconcile")
public class OpenReconcileController {
    private final TaskService taskService;
    private final AlarmService alarmService;
    private final ConnectionManager connectionManager;

    public OpenReconcileController(TaskService taskService, AlarmService alarmService,
                                   ConnectionManager connectionManager) {
        this.taskService = taskService;
        this.alarmService = alarmService;
        this.connectionManager = connectionManager;
    }

    @PostMapping("/task/query")
    @Operation(summary = "增量拉任务（按 last_update_time 水位线）")
    public ApiResponse<ReconcileResult<TaskEntity>> queryTasks(@RequestBody ReconcileQuery q) {
        long now = System.currentTimeMillis();
        List<TaskEntity> rows = taskService.queryReconcile(q.appId(), q.sinceMillis(), q.limit());
        return ApiResponse.success(new ReconcileResult<>(now, rows.size(), rows));
    }

    @PostMapping("/alarm/query")
    @Operation(summary = "增量拉报警（按 last_update_time 水位线）")
    public ApiResponse<ReconcileResult<AlarmEntity>> queryAlarms(@RequestBody ReconcileQuery q) {
        long now = System.currentTimeMillis();
        List<AlarmEntity> rows = alarmService.queryReconcile(q.appId(), q.sinceMillis(), q.limit());
        return ApiResponse.success(new ReconcileResult<>(now, rows.size(), rows));
    }

    @PostMapping("/snapshot")
    @Operation(summary = "断线重连后一把全量重同步")
    public ApiResponse<SnapshotResult> snapshot(@RequestBody SnapshotQuery q) {
        long now = System.currentTimeMillis();
        ConnectionSnapshot conn = connectionManager.isOpen(q.appId()) ? connectionManager.snapshot(q.appId()) : null;
        return ApiResponse.success(new SnapshotResult(now, conn,
                alarmService.listActive(q.appId()),
                taskService.queryReconcile(q.appId(), 0, 50)));
    }

    public record ReconcileQuery(Long appId, long sinceMillis, int limit) {
    }

    public record SnapshotQuery(Long appId) {
    }

    public record ReconcileResult<T>(long serverTimeMillis, int count, List<T> rows) {
    }

    public record SnapshotResult(long serverTimeMillis, ConnectionSnapshot connection,
                                 List<AlarmEntity> alarms, List<TaskEntity> tasks) {
    }
}
