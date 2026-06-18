package com.cayleywcs.simulator;

import com.cayleywcs.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仿真控制接口：联调时注入/清除故障码、复位设备、查看设备内部状态。
 */
@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Simulator")
public class SimulatorController {
    private final StackerSimulatorRegistry registry;

    public SimulatorController(StackerSimulatorRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/simulator/inject-fault")
    @Operation(summary = "向仿真设备注入故障码")
    public ApiResponse<Boolean> injectFault(@RequestBody FaultRequest request) {
        registry.getOrCreate(request.appId()).injectFault(request.code());
        return ApiResponse.success(true);
    }

    @PostMapping("/simulator/clear-fault")
    public ApiResponse<Boolean> clearFault(@RequestBody FaultRequest request) {
        registry.find(request.appId()).ifPresent(StackerDeviceState::clearFault);
        return ApiResponse.success(true);
    }

    @PostMapping("/simulator/reset")
    public ApiResponse<Boolean> reset(@RequestBody FaultRequest request) {
        registry.reset(request.appId());
        return ApiResponse.success(true);
    }

    @PostMapping("/simulator/snapshot")
    @Operation(summary = "查看仿真设备内部状态")
    public ApiResponse<Map<String, Object>> snapshot(@RequestBody FaultRequest request) {
        return ApiResponse.success(registry.find(request.appId())
                .map(StackerDeviceState::snapshot).orElse(Map.of()));
    }

    public record FaultRequest(Long appId, int code) {
    }
}
