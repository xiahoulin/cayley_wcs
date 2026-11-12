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
import java.util.Map;
import java.util.Set;

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
    @Operation(summary = "对接应用下发任务（access_token + 设备授权，业务编号幂等）")
    public ApiResponse<Map<String,Object>> dispatchHttp(@RequestBody Map<String,Object> body, HttpServletRequest http) {
        var accepted=dispatch(RequestPayloads.parse(body, Set.of("appId","taskNo","priority","wmsRef","payload"),
                TaskService.DispatchRequest.class), http);
        return ApiResponse.success(TaskPublicView.of(accepted.data()));
    }

    public ApiResponse<TaskEntity> dispatch(TaskService.DispatchRequest request, HttpServletRequest http) {
        accessGuard.assertCanCommand(http, request.appId(), "task/dispatch");
        return ApiResponse.success(taskService.dispatch(request, accessGuard.resolveCaller(http)));
    }
}
