package com.cayleywcs.protocol;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.protocol.entity.ProtocolRevisionEntity;
import com.cayleywcs.adapter.PointTypeRegistry;
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
@Tag(name = "Protocol")
public class ProtocolController {
    private final ProtocolService protocolService;

    public ProtocolController(ProtocolService protocolService) {
        this.protocolService = protocolService;
    }

    @PostMapping("/protocol/list")
    @Operation(summary = "协议分页列表")
    public ApiResponse<PageData<ProtocolEntity>> list(@RequestBody PageSearch pageSearch) {
        return ApiResponse.success(protocolService.page(pageSearch));
    }

    @PostMapping("/protocol/all")
    @Operation(summary = "协议全量（下拉用）")
    public ApiResponse<List<ProtocolEntity>> all() {
        return ApiResponse.success(protocolService.listAll());
    }

    @PostMapping("/protocol/templates")
    public ApiResponse<List<ProtocolEntity>> templates() {
        return ApiResponse.success(protocolService.listTemplates());
    }

    @PostMapping("/protocol/detail")
    public ApiResponse<ProtocolEntity> detail(@RequestBody IdRequest request) {
        return ApiResponse.success(protocolService.getById(request.id()));
    }

    @PostMapping("/protocol/create")
    public ApiResponse<ProtocolEntity> create(@RequestBody ProtocolEntity entity) {
        return ApiResponse.success(protocolService.create(entity));
    }

    @PostMapping("/protocol/update")
    public ApiResponse<ProtocolEntity> update(@RequestBody ProtocolEntity entity) {
        return ApiResponse.success(protocolService.update(entity));
    }

    @PostMapping("/protocol/delete")
    public ApiResponse<Boolean> delete(@RequestBody IdRequest request) {
        return ApiResponse.success(protocolService.delete(request.id()));
    }

    // ===== 点位 =====

    @PostMapping("/protocol/point/list")
    @Operation(summary = "按协议查点位")
    public ApiResponse<List<ProtocolPointEntity>> listPoints(@RequestBody IdRequest request) {
        return ApiResponse.success(protocolService.listPoints(request.id()));
    }

    @PostMapping("/protocol/point/create")
    public ApiResponse<ProtocolPointEntity> createPoint(@RequestBody ProtocolPointEntity entity) {
        return ApiResponse.success(protocolService.createPoint(entity));
    }

    @PostMapping("/protocol/point/update")
    public ApiResponse<ProtocolPointEntity> updatePoint(@RequestBody ProtocolPointEntity entity) {
        return ApiResponse.success(protocolService.updatePoint(entity));
    }

    @PostMapping("/protocol/point/delete")
    public ApiResponse<Boolean> deletePoint(@RequestBody PublishRequest request) {
        return ApiResponse.success(protocolService.deletePoint(request.id(), request.row_version()));
    }

    public record PublishRequest(Long id, Long row_version) { }
    public record TypeRequest(String protocol_type) { }
    public record TypePairRequest(String source_type, String target_type) { }
    public record CloneRequest(Long id, Long app_id) { }

    @PostMapping("/protocol/clone")
    public ApiResponse<ProtocolEntity> cloneDraft(@RequestBody CloneRequest request) {
        return ApiResponse.success(protocolService.cloneDraft(request.id(), request.app_id()));
    }
    @PostMapping("/protocol/publish")
    public ApiResponse<ProtocolEntity> publish(@RequestBody PublishRequest request) {
        return ApiResponse.success(protocolService.publish(request.id(), request.row_version()));
    }
    @PostMapping("/protocol/revisions")
    public ApiResponse<List<ProtocolRevisionEntity>> revisions(@RequestBody IdRequest request) {
        return ApiResponse.success(protocolService.revisions(request.id()));
    }
    @PostMapping("/protocol/type/options")
    public ApiResponse<List<String>> typeOptions(@RequestBody TypeRequest request) {
        return ApiResponse.success(PointTypeRegistry.options(request.protocol_type()));
    }
    @PostMapping("/protocol/type/matrix")
    public ApiResponse<List<java.util.Map<String, Object>>> typeMatrix(@RequestBody TypePairRequest request) {
        return ApiResponse.success(PointTypeRegistry.pairMatrix(request.source_type(), request.target_type()));
    }
}
