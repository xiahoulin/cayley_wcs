package com.cayleywcs.openapi;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.ConnectionSnapshot;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.entity.TaskEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.cayleywcs.task.TaskPublicView;
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
 * 用法：处理 rows 后按 id 去重；下次 sinceMillis 传**已处理最后一行的 last_update_time**
 * （count==limit 截断时传 serverTimeMillis 会永久漏行；查询含等值重拉，id 去重兜底）。
 * 越权防护（F8）：按验签身份收敛可见范围——指定 appId 须为自身或已绑定授权；appId 缺省仅返回可见集合。
 */
@RestController
@RequestMapping(value = "/open", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "OpenReconcile")
public class OpenReconcileController {
    private final TaskService taskService;
    private final AlarmService alarmService;
    private final ConnectionManager connectionManager;
    private final OpenAccessGuard accessGuard;

    public OpenReconcileController(TaskService taskService, AlarmService alarmService,
                                   ConnectionManager connectionManager, OpenAccessGuard accessGuard) {
        this.taskService = taskService;
        this.alarmService = alarmService;
        this.connectionManager = connectionManager;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/task/query")
    @Operation(summary = "增量拉任务（按 last_update_time 水位线；越权收敛到授权范围）")
    public ApiResponse<ReconcileResult<Map<String,Object>>> queryTasks(@RequestBody ReconcileQuery q, HttpServletRequest http) {
        long now = System.currentTimeMillis();
        List<TaskEntity> rows;
        if (q.appId() != null) {
            accessGuard.assertCanQuery(http, q.appId(), "task/query");
            rows = taskService.queryReconcile(q.appId(), q.sinceMillis(), q.limit());
        } else {
            rows = taskService.queryReconcile(accessGuard.visibleAppIds(http, "task/query"), q.sinceMillis(), q.limit());
        }
        return ApiResponse.success(new ReconcileResult<>(now, rows.size(), rows.stream().map(TaskPublicView::of).toList()));
    }

    @PostMapping("/alarm/query")
    @Operation(summary = "增量拉报警（按 last_update_time 水位线；越权收敛到授权范围）")
    public ApiResponse<ReconcileResult<Map<String,Object>>> queryAlarms(@RequestBody ReconcileQuery q, HttpServletRequest http) {
        long now = System.currentTimeMillis();
        List<AlarmEntity> rows;
        if (q.appId() != null) {
            accessGuard.assertCanQuery(http, q.appId(), "alarm/query");
            rows = alarmService.queryReconcile(q.appId(), q.sinceMillis(), q.limit());
        } else {
            rows = alarmService.queryReconcile(accessGuard.visibleAppIds(http, "alarm/query"), q.sinceMillis(), q.limit());
        }
        return ApiResponse.success(new ReconcileResult<>(now, rows.size(), rows.stream().map(OpenPublicViews::alarm).toList()));
    }

    @PostMapping("/snapshot")
    @Operation(summary = "断线重连后一把全量重同步（appId 必填，须有访问授权）")
    public ApiResponse<SnapshotResult> snapshot(@RequestBody SnapshotQuery q, HttpServletRequest http) {
        if (q.appId() == null) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "appId 必填");
        }
        accessGuard.assertCanQuery(http, q.appId(), "snapshot");
        long now = System.currentTimeMillis();
        ConnectionSnapshot conn = connectionManager.isOpen(q.appId()) ? connectionManager.snapshot(q.appId()) : null;
        Map<String,Object> publicConnection=null;
        if (conn!=null) {
            publicConnection=new LinkedHashMap<>();
            publicConnection.put("appId",conn.appId()); publicConnection.put("appCode",conn.appCode());
            publicConnection.put("state",conn.state()); publicConnection.put("lastHeartbeatAt",conn.lastHeartbeatAt());
            publicConnection.put("protocolType",conn.protocolType()); publicConnection.put("deviceType",conn.deviceType());
            publicConnection.put("quality","bad"); publicConnection.put("capturedAt",conn.lastHeartbeatAt());
            publicConnection.put("payload",null);
            if ("RUNNING".equals(conn.state())) {
                try {
                    publicConnection.put("payload",connectionManager.validatedResponsePayload(q.appId()));
                    publicConnection.put("quality","good"); publicConnection.put("capturedAt",now);
                } catch (WcsException ex) {
                    publicConnection.put("errorCode",ex.getCode()); publicConnection.put("errorMessage",ex.getMessage());
                }
            }
        }
        return ApiResponse.success(new SnapshotResult(now, publicConnection,
                alarmService.listActive(q.appId()).stream().map(OpenPublicViews::alarm).toList(),
                taskService.queryReconcile(q.appId(), 0, 50).stream().map(TaskPublicView::of).toList()));
    }

    public record ReconcileQuery(Long appId, long sinceMillis, int limit) {
    }

    public record SnapshotQuery(Long appId) {
    }

    public record ReconcileResult<T>(long serverTimeMillis, int count, List<T> rows) {
    }

    public record SnapshotResult(long serverTimeMillis, Map<String,Object> connection,
                                 List<Map<String,Object>> alarms, List<Map<String,Object>> tasks) {
    }
}
