package com.cayleywcs.alarm;

import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.common.api.ApiResponse;
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
@Tag(name = "Alarm")
public class AlarmController {
    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
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

    public record ActiveQuery(Long appId) {
    }

    public record AckRequest(Long id, String ackBy) {
    }

    public record ClearRequest(Long id) {
    }
}
