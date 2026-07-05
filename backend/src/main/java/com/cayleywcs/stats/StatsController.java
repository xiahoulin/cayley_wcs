package com.cayleywcs.stats;

import com.cayleywcs.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运行统计（M11，管理端 JWT）：任务汇总 / 设备利用率 / 报警 TOP。时间参数毫秒时间戳，0=不限（利用率默认近 24h）。 */
@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Stats")
public class StatsController {
    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @PostMapping("/stats/task-summary")
    @Operation(summary = "任务汇总：量/终态分布/完成率/平均执行时长/失败码 TOP")
    public ApiResponse<StatsService.TaskSummary> taskSummary(@RequestBody RangeQuery q) {
        return ApiResponse.success(statsService.taskSummary(q.appId(), q.fromMillis(), q.toMillis()));
    }

    @PostMapping("/stats/device-utilization")
    @Operation(summary = "设备利用率：重放连接事件日志计算 RUNNING 时长占比")
    public ApiResponse<List<StatsService.DeviceUtilization>> deviceUtilization(@RequestBody RangeQuery q) {
        return ApiResponse.success(statsService.deviceUtilization(q.appId(), q.fromMillis(), q.toMillis()));
    }

    @PostMapping("/stats/alarm-top")
    @Operation(summary = "报警 TOP：按故障码聚合次数降序")
    public ApiResponse<List<StatsService.AlarmTop>> alarmTop(@RequestBody AlarmTopQuery q) {
        return ApiResponse.success(statsService.alarmTop(q.appId(), q.fromMillis(), q.toMillis(), q.topN()));
    }

    public record RangeQuery(Long appId, long fromMillis, long toMillis) {
    }

    public record AlarmTopQuery(Long appId, long fromMillis, long toMillis, int topN) {
    }
}
