package com.cayleywcs.device.agv;

import com.cayleywcs.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/agv",consumes="application/json",produces="application/json")
public class AgvController {
    private final AgvReadService service;
    public AgvController(AgvReadService service) { this.service=service; }
    public record AppId(Long appId) { }
    @PostMapping("/upgrade") public ApiResponse<Map<String,Object>> upgrade(@RequestBody AppId request) { return ApiResponse.success(service.upgrade(request.appId())); }
    @PostMapping("/create") public ApiResponse<Map<String,Object>> create(@RequestBody AgvReadService.Create request) { return ApiResponse.success(service.create(request)); }
    @PostMapping("/list") public ApiResponse<List<Map<String,Object>>> list(@RequestBody Map<String,Object> ignored) { return ApiResponse.success(service.list()); }
    @PostMapping("/state") public ApiResponse<Map<String,Object>> state(@RequestBody AppId request) { return ApiResponse.success(service.state(request.appId())); }
}
