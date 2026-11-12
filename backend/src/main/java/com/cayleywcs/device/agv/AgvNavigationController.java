package com.cayleywcs.device.agv;

import com.cayleywcs.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/agv/navigation",consumes="application/json",produces="application/json")
public class AgvNavigationController {
    private final AgvNavigationService service;
    public AgvNavigationController(AgvNavigationService service) {this.service=service;}
    @PostMapping("/enable") public ApiResponse<Map<String,Object>> enable(@RequestBody AgvController.AppId r) {return ApiResponse.success(service.enable(r.appId()));}
    @PostMapping("/state") public ApiResponse<Map<String,Object>> state(@RequestBody AgvController.AppId r) {return ApiResponse.success(service.state(r.appId()));}
    @PostMapping("/send") public ApiResponse<Map<String,Object>> send(@RequestBody AgvNavigationService.Command r) {return ApiResponse.success(service.send(r));}
    @PostMapping("/acknowledge") public ApiResponse<Map<String,Object>> acknowledge(@RequestBody AgvNavigationService.Acknowledge r) {return ApiResponse.success(service.acknowledge(r));}
}
