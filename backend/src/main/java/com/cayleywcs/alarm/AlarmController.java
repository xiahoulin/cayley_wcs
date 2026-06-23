package com.cayleywcs.alarm;

import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.connection.ConnectionManager;
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
@Tag(name = "Alarm")
public class AlarmController {
    private final AlarmService alarmService;
    private final ConnectionManager connectionManager;

    public AlarmController(AlarmService alarmService, ConnectionManager connectionManager) {
        this.alarmService = alarmService;
        this.connectionManager = connectionManager;
    }

    @PostMapping("/alarm/list")
    @Operation(summary = "报警分页列表")
    public ApiResponse<PageData<AlarmEntity>> list(@RequestBody PageSearch pageSearch) {
        return ApiResponse.success(alarmService.page(pageSearch));
    }

    @PostMapping("/alarm/active")
    @Operation(summary = "活动报警(raised/ack)")
    public ApiResponse<List<AlarmEntity>> active(@RequestBody ActiveQuery query) {
        return ApiResponse.success(alarmService.listActive(query.appId()));
    }

    @PostMapping("/alarm/ack")
    public ApiResponse<AlarmEntity> ack(@RequestBody AckRequest request) {
        return ApiResponse.success(alarmService.ack(request.id(), request.ackBy()));
    }

    @PostMapping("/alarm/clear")
    public ApiResponse<Boolean> clear(@RequestBody ClearRequest request) {
        return ApiResponse.success(alarmService.clear(request.id()));
    }

    @PostMapping("/alarm/reset-fault")
    @Operation(summary = "设备故障复位：写 cmd_ResetFault=1→保持→0 触发 PLC 侧异常处理，并清除 WCS 侧活动报警")
    public ApiResponse<Boolean> resetFault(@RequestBody IdRequest request) {
        try {
            connectionManager.resetFault(request.id());
        } finally {
            // 设备侧复位成败均清 WCS 活动报警（避免 resetFault 异常时报警残留）
            alarmService.clearActiveFaults(request.id());
        }
        return ApiResponse.success(true);
    }

    public record ActiveQuery(Long appId) {
    }

    public record AckRequest(Long id, String ackBy) {
    }

    public record ClearRequest(Long id) {
    }
}
