package com.cayleywcs.health;

import com.cayleywcs.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of("status", "UP", "service", "cayleywcs-backend"));
    }

    @PostMapping("/health")
    public ApiResponse<Map<String, Object>> healthPost() {
        return health();
    }
}
