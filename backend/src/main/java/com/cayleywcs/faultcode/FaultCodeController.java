package com.cayleywcs.faultcode;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.faultcode.entity.FaultCodeEntity;
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
@Tag(name = "FaultCode")
public class FaultCodeController {
    private final FaultCodeService faultCodeService;
    private final FaultCodeResolver faultCodeResolver;

    public FaultCodeController(FaultCodeService faultCodeService, FaultCodeResolver faultCodeResolver) {
        this.faultCodeService = faultCodeService;
        this.faultCodeResolver = faultCodeResolver;
    }

    @PostMapping("/fault-code/list")
    @Operation(summary = "按协议查故障码")
    public ApiResponse<List<FaultCodeEntity>> list(@RequestBody IdRequest request) {
        return ApiResponse.success(faultCodeService.listByProtocol(request.id()));
    }

    @PostMapping("/fault-code/create")
    public ApiResponse<FaultCodeEntity> create(@RequestBody FaultCodeEntity entity) {
        return ApiResponse.success(faultCodeService.create(entity));
    }

    @PostMapping("/fault-code/update")
    public ApiResponse<FaultCodeEntity> update(@RequestBody FaultCodeEntity entity) {
        return ApiResponse.success(faultCodeService.update(entity));
    }

    @PostMapping("/fault-code/delete")
    public ApiResponse<Boolean> delete(@RequestBody IdRequest request) {
        return ApiResponse.success(faultCodeService.delete(request.id()));
    }

    @PostMapping("/fault-code/resolve")
    @Operation(summary = "解析故障码（命中返回维护信息，未维护返回统一兜底）")
    public ApiResponse<FaultInfo> resolve(@RequestBody ResolveRequest request) {
        return ApiResponse.success(faultCodeResolver.resolve(request.protocolId(), request.code()));
    }

    public record ResolveRequest(Long protocolId, long code) {
    }
}
