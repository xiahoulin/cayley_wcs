package com.cayleywcs.connection;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接监控/运维接口（需求 4）：手动建连/断开/重连 + 槽位与状态查询，供前端连接监控页。
 */
@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Connection")
public class ConnectionController {
    private final ConnectionManager connectionManager;

    public ConnectionController(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @PostMapping("/connection/open")
    @Operation(summary = "建立连接（满则提示等待，超时自动回收）")
    public ApiResponse<ConnectionSnapshot> open(@RequestBody IdRequest request) {
        return ApiResponse.success(connectionManager.open(request.id()));
    }

    @PostMapping("/connection/close")
    @Operation(summary = "断开连接并回收连接槽")
    public ApiResponse<Boolean> close(@RequestBody IdRequest request) {
        connectionManager.close(request.id());
        return ApiResponse.success(true);
    }

    @PostMapping("/connection/reconnect")
    public ApiResponse<ConnectionSnapshot> reconnect(@RequestBody IdRequest request) {
        return ApiResponse.success(connectionManager.reconnect(request.id()));
    }

    @PostMapping("/connection/detail")
    public ApiResponse<ConnectionSnapshot> detail(@RequestBody IdRequest request) {
        return ApiResponse.success(connectionManager.snapshot(request.id()));
    }

    @PostMapping("/connection/status")
    @Operation(summary = "全部连接状态 + 连接槽占用")
    public ApiResponse<StatusView> status() {
        return ApiResponse.success(new StatusView(connectionManager.snapshots(), connectionManager.slotUsage()));
    }

    @PostMapping("/connection/read")
    @Operation(summary = "手动读取单点")
    public ApiResponse<Object> read(@RequestBody PointIoRequest request) {
        return ApiResponse.success(connectionManager.readPoint(request.appId(), request.field()));
    }

    @PostMapping("/connection/write")
    @Operation(summary = "手动写入单点")
    public ApiResponse<Boolean> write(@RequestBody PointIoRequest request) {
        connectionManager.writePoint(request.appId(), request.field(), request.value());
        return ApiResponse.success(true);
    }

    public record StatusView(List<ConnectionSnapshot> connections, ConnectionManager.SlotUsage slots) {
    }

    public record PointIoRequest(Long appId, String field, Object value) {
    }
}
