package com.cayleywcs.telemetry.api;

import com.cayleywcs.common.api.*;
import com.cayleywcs.telemetry.TelemetryService;
import com.cayleywcs.telemetry.SensorApplicationService;
import com.cayleywcs.telemetry.entity.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/telemetry",consumes="application/json",produces="application/json")
public class TelemetryController {
    private final TelemetryService service;
    private final SensorApplicationService applications;
    public TelemetryController(TelemetryService service,SensorApplicationService applications) { this.service=service;this.applications=applications; }
    public record App(Long agvAppId) { }
    public record Id(Long gatewayId) { }
    @PostMapping("/gateway/applications") public ApiResponse<List<SensorApplicationService.Link>> applications(@RequestBody Id request) { return ApiResponse.success(applications.provision(request.gatewayId())); }
    @PostMapping("/gateway/list") public ApiResponse<List<SensorGatewayEntity>> list(@RequestBody App request) { return ApiResponse.success(service.list(request.agvAppId())); }
    @PostMapping("/gateway/configure") public ApiResponse<SensorGatewayEntity> configure(@RequestBody TelemetryService.Configure request) { return ApiResponse.success(service.configure(request)); }
    @PostMapping("/gateway/enable") public ApiResponse<SensorGatewayEntity> enable(@RequestBody TelemetryService.Enable request) { return ApiResponse.success(service.enable(request)); }
    @PostMapping("/gateway/power") public ApiResponse<SensorGatewayEntity> power(@RequestBody TelemetryService.Power request) { return ApiResponse.success(service.power(request)); }
    @PostMapping("/latest") public ApiResponse<Map<String,Object>> latest(@RequestBody Id request) { return ApiResponse.success(service.latest(request.gatewayId())); }
    @PostMapping("/history") public ApiResponse<PageData<TelemetryCycleEntity>> history(@RequestBody TelemetryService.History request) { return ApiResponse.success(service.history(request)); }
}
