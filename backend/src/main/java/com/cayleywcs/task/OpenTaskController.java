package com.cayleywcs.task;

import com.cayleywcs.application.security.AppKeyAuthFilter;
import com.cayleywcs.binding.BindingService;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.task.entity.TaskEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放接口：WMS/上位系统下发任务（经 AppKeyAuthFilter 鉴权，路径 /open/**）。
 * 越权防护：调用方身份(验签得到的 ATTR_APP_ID) 必须被授权指挥 body.appId 指定的设备（wcs_app_binding），否则 FORBIDDEN。
 */
@RestController
@RequestMapping(value = "/open/task", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "OpenTask")
public class OpenTaskController {
    private static final Logger log = LoggerFactory.getLogger(OpenTaskController.class);

    private final TaskService taskService;
    private final BindingService bindingService;

    public OpenTaskController(TaskService taskService, BindingService bindingService) {
        this.taskService = taskService;
        this.bindingService = bindingService;
    }

    @PostMapping("/dispatch")
    @Operation(summary = "WMS 下发任务（AppKey 鉴权 + 绑定授权，幂等）")
    public ApiResponse<TaskEntity> dispatch(@RequestBody TaskService.DispatchRequest request, HttpServletRequest http) {
        Object caller = http.getAttribute(AppKeyAuthFilter.ATTR_APP_ID);
        if (caller instanceof Long callerId) {
            // 越权判定按调用方租户隔离（与唯一索引含 tenant 对齐），防止跨租户绑定误放行。
            Object tenant = http.getAttribute(AppKeyAuthFilter.ATTR_APP_TENANT_ID);
            Long tenantId = (tenant instanceof Long t) ? t : null;
            bindingService.assertAllowed(callerId, request.appId(), tenantId);
        } else {
            // caller 仅在 appkey 鉴权整体关闭(cayleywcs.openapi.appkey-enabled=false)时为 null：
            // 无验签身份可校验，越权防护被旁路。显式 WARN，避免“关 appkey=静默关越权防护”。
            log.warn("AppKey 鉴权已关闭，/open/task/dispatch 跳过绑定越权校验 (appId={}, taskNo={})。"
                    + "生产环境请勿关闭 cayleywcs.openapi.appkey-enabled。", request.appId(), request.taskNo());
        }
        return ApiResponse.success(taskService.dispatch(request));
    }
}
