package com.cayleywcs.application;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Application")
public class ApplicationController {
    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/application/list")
    @Operation(summary = "应用分页列表")
    public ApiResponse<PageData<ApplicationEntity>> list(@RequestBody PageSearch pageSearch) {
        return ApiResponse.success(applicationService.page(pageSearch));
    }

    @PostMapping("/application/all")
    public ApiResponse<List<ApplicationEntity>> all() {
        return ApiResponse.success(applicationService.listAll());
    }

    @PostMapping("/application/detail")
    public ApiResponse<ApplicationEntity> detail(@RequestBody IdRequest request) {
        return ApiResponse.success(applicationService.getById(request.id()));
    }

    @PostMapping("/application/create")
    public ApiResponse<ApplicationEntity> create(@RequestBody ApplicationEntity entity) {
        return ApiResponse.success(applicationService.create(entity));
    }

    @PostMapping("/application/update")
    public ApiResponse<ApplicationEntity> update(@RequestBody ApplicationEntity entity) {
        return ApiResponse.success(applicationService.update(entity));
    }

    @PostMapping("/application/delete")
    public ApiResponse<Boolean> delete(@RequestBody IdRequest request) {
        return ApiResponse.success(applicationService.delete(request.id()));
    }

    @PostMapping("/application/reset-secret")
    @Operation(summary = "重置 AppSecret")
    public ApiResponse<ApplicationEntity> resetSecret(@RequestBody IdRequest request) {
        return ApiResponse.success(applicationService.resetSecret(request.id()));
    }
}
