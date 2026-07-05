package com.cayleywcs.task;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.task.entity.TaskEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Task")
public class TaskController {
    private final TaskService taskService;
    private final ApplicationService applicationService;

    public TaskController(TaskService taskService, ApplicationService applicationService) {
        this.taskService = taskService;
        this.applicationService = applicationService;
    }

    @PostMapping("/task/list")
    @Operation(summary = "任务分页列表")
    public ApiResponse<PageData<TaskEntity>> list(@RequestBody PageSearch pageSearch) {
        return ApiResponse.success(taskService.page(pageSearch));
    }

    @PostMapping("/task/detail")
    public ApiResponse<TaskEntity> detail(@RequestBody IdRequest request) {
        return ApiResponse.success(taskService.getById(request.id()));
    }

    @PostMapping("/task/create")
    @Operation(summary = "手动创建任务")
    public ApiResponse<TaskEntity> create(@RequestBody TaskEntity entity) {
        return ApiResponse.success(taskService.create(entity));
    }

    @PostMapping("/task/cancel")
    public ApiResponse<Boolean> cancel(@RequestBody IdRequest request) {
        return ApiResponse.success(taskService.cancel(request.id()));
    }

    @PostMapping("/task/pause-app")
    @Operation(summary = "暂停设备派工（在飞任务跑完，新任务滞留 pending）")
    public ApiResponse<Boolean> pauseApp(@RequestBody AppIdRequest request) {
        applicationService.updateWorkMode(request.appId(), "paused");
        return ApiResponse.success(true);
    }

    @PostMapping("/task/resume-app")
    @Operation(summary = "恢复设备派工")
    public ApiResponse<Boolean> resumeApp(@RequestBody AppIdRequest request) {
        applicationService.updateWorkMode(request.appId(), "normal");
        return ApiResponse.success(true);
    }

    @PostMapping("/task/reprioritize")
    @Operation(summary = "改任务优先级（false=任务不存在或并发冲突；先 /task/detail 核对再重试）")
    public ApiResponse<Boolean> reprioritize(@RequestBody ReprioritizeRequest request) {
        return ApiResponse.success(taskService.reprioritize(request.id(), request.priority()));
    }

    @PostMapping("/task/retry")
    @Operation(summary = "重试失败的独立任务（链子任务走链级处理；false=任务不存在或并发冲突）")
    public ApiResponse<Boolean> retry(@RequestBody IdRequest request) {
        return ApiResponse.success(taskService.retryTask(request.id()));
    }

    public record AppIdRequest(Long appId) {
    }

    public record ReprioritizeRequest(Long id, Long priority) {
    }
}
