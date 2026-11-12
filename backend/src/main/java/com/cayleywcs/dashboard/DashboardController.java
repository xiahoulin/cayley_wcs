package com.cayleywcs.dashboard;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.task.RequestPayloads;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service){this.service=service;}
    @PostMapping("/dashboard/devices") public ApiResponse<List<Map<String,Object>>> devices(){return ApiResponse.success(service.devices());}
    @PostMapping("/dashboard/selection") public ApiResponse<Boolean> selection(@RequestBody Map<String,Object> body){
        service.saveSelection(RequestPayloads.parse(body,Set.of("appId","revisionId","fields"),DashboardService.Selection.class));
        return ApiResponse.success(true);
    }
}
