package com.cayleywcs.task;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.openapi.OpenAccessGuard;
import com.cayleywcs.task.entity.TaskEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放接口：WMS/上位系统下发任务（经 AppKeyAuthFilter 鉴权，路径 /open/**）。
 * 越权防护：调用方身份(验签得到的 ATTR_APP_ID) 必须被授权指挥 body.appId 指定的设备（wcs_app_binding），
 * 否则 FORBIDDEN——统一走 {@link OpenAccessGuard}。
 */
@RestController
@RequestMapping(value = "/open/task", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "OpenTask")
public class OpenTaskController {
    private final TaskService taskService;
    private final OpenAccessGuard accessGuard;

    public OpenTaskController(TaskService taskService, OpenAccessGuard accessGuard) {
        this.taskService = taskService;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/dispatch")
    @Operation(summary = "WMS 下发任务（AppKey 鉴权 + 绑定授权，幂等）")
    public ApiResponse<TaskEntity> dispatch(@RequestBody TaskService.DispatchRequest request, HttpServletRequest http) {
        accessGuard.assertCanCommand(http, request.appId(), "task/dispatch");
        return ApiResponse.success(taskService.dispatch(request));
    }
}
