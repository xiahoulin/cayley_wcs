package com.cayleywcs.binding;

import com.cayleywcs.binding.entity.AppBindingEntity;
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

/**
 * 上下位绑定授权管理（需求 3 安全增强）：维护「上位调用方 → 下位设备」的指挥授权。
 */
@RestController
@RequestMapping(value = "/binding", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Binding")
public class BindingController {
    private final BindingService bindingService;

    public BindingController(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    @PostMapping("/list")
    @Operation(summary = "绑定分页列表")
    public ApiResponse<PageData<AppBindingEntity>> list(@RequestBody PageSearch pageSearch) {
        return ApiResponse.success(bindingService.page(pageSearch));
    }

    @PostMapping("/all")
    public ApiResponse<List<AppBindingEntity>> all() {
        return ApiResponse.success(bindingService.listAll());
    }

    @PostMapping("/detail")
    public ApiResponse<AppBindingEntity> detail(@RequestBody IdRequest request) {
        return ApiResponse.success(bindingService.getById(request.id()));
    }

    @PostMapping("/create")
    public ApiResponse<AppBindingEntity> create(@RequestBody AppBindingEntity entity) {
        return ApiResponse.success(bindingService.create(entity));
    }

    @PostMapping("/update")
    public ApiResponse<AppBindingEntity> update(@RequestBody AppBindingEntity entity) {
        return ApiResponse.success(bindingService.update(entity));
    }

    @PostMapping("/delete")
    public ApiResponse<Boolean> delete(@RequestBody IdRequest request) {
        return ApiResponse.success(bindingService.delete(request.id()));
    }

    @PostMapping("/granted")
    @Operation(summary = "查某上位侧应用已授权指挥的下位侧应用 id 列表（按应用授权页回显）")
    public ApiResponse<List<Long>> granted(@RequestBody UpstreamRequest request) {
        return ApiResponse.success(bindingService.grantedDownstreamIds(request.upstreamAppId()));
    }

    @PostMapping("/grant")
    @Operation(summary = "整批授权：把该上位侧应用的可指挥下位侧应用集合设置为给定列表（幂等，忽略自指）")
    public ApiResponse<List<Long>> grant(@RequestBody GrantRequest request) {
        return ApiResponse.success(
                bindingService.grant(request.upstreamAppId(), request.downstreamAppIds(), request.scope()));
    }

    public record UpstreamRequest(Long upstreamAppId) {
    }

    public record GrantRequest(Long upstreamAppId, List<Long> downstreamAppIds, String scope) {
    }
}
