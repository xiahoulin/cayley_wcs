package com.cayleywcs.task;

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

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
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
}
