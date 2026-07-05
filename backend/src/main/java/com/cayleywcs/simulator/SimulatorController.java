package com.cayleywcs.simulator;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仿真控制接口：联调时注入/清除故障码、复位设备、查看设备内部状态。
 * M9 泛化：按应用 device_type 建对应仿真设备（堆垛机/输送线/电梯/自动门）；无此应用（如 OPC UA 仿真固定 appId）默认堆垛机。
 */
@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Simulator")
public class SimulatorController {
    private final DeviceSimRegistry registry;
    private final DeviceSimFactory simFactory;
    private final ApplicationService applicationService;

    public SimulatorController(DeviceSimRegistry registry, DeviceSimFactory simFactory,
                               ApplicationService applicationService) {
        this.registry = registry;
        this.simFactory = simFactory;
        this.applicationService = applicationService;
    }

    @PostMapping("/simulator/inject-fault")
    @Operation(summary = "向仿真设备注入故障码")
    public ApiResponse<Boolean> injectFault(@RequestBody FaultRequest request) {
        sim(request.appId()).injectFault(request.code());
        return ApiResponse.success(true);
    }

    @PostMapping("/simulator/clear-fault")
    public ApiResponse<Boolean> clearFault(@RequestBody FaultRequest request) {
        registry.find(request.appId()).ifPresent(DeviceSim::clearFault);
        return ApiResponse.success(true);
    }

    @PostMapping("/simulator/reset")
    public ApiResponse<Boolean> reset(@RequestBody FaultRequest request) {
        sim(request.appId()).reset(); // 原地复位（保留实例，不脱钩已连接适配器/OPC UA 命名空间）
        return ApiResponse.success(true);
    }

    @PostMapping("/simulator/snapshot")
    @Operation(summary = "查看仿真设备内部状态")
    public ApiResponse<Map<String, Object>> snapshot(@RequestBody FaultRequest request) {
        return ApiResponse.success(registry.find(request.appId())
                .map(DeviceSim::snapshot).orElse(Map.of()));
    }

    private DeviceSim sim(Long appId) {
        return registry.getOrCreate(appId, supplierFor(appId));
    }

    /** 按应用 device_type 选仿真构造器；无此应用（OPC UA 仿真固定 appId 等）时回落堆垛机默认。 */
    private Supplier<? extends DeviceSim> supplierFor(Long appId) {
        String deviceType = null;
        try {
            deviceType = applicationService.getById(appId).getDevice_type();
        } catch (RuntimeException ignore) {
            // 无此应用：走工厂默认（堆垛机）
        }
        return simFactory.supplier(deviceType);
    }

    public record FaultRequest(Long appId, int code) {
    }
}
