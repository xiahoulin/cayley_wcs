package com.cayleywcs.task;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.task.entity.TaskEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放接口：WMS/上位系统下发任务（经 AppKeyAuthFilter 鉴权，路径 /open/**）。
 */
@RestController
@RequestMapping(value = "/open/task", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "OpenTask")
public class OpenTaskController {
    private final TaskService taskService;

    public OpenTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/dispatch")
    @Operation(summary = "WMS 下发任务（AppKey 鉴权，幂等）")
    public ApiResponse<TaskEntity> dispatch(@RequestBody TaskService.DispatchRequest request) {
        return ApiResponse.success(taskService.dispatch(request));
    }
}
