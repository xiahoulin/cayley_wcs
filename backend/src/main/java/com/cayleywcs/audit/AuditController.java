package com.cayleywcs.audit;

import com.cayleywcs.audit.entity.ConnectionLogEntity;
import com.cayleywcs.audit.entity.MessageLogEntity;
import com.cayleywcs.common.api.ApiResponse;
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
@Tag(name = "Audit")
public class AuditController {
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @PostMapping("/audit/connection-log/list")
    @Operation(summary = "连接事件审计")
    public ApiResponse<List<ConnectionLogEntity>> connectionLogs(@RequestBody AuditQuery query) {
        return ApiResponse.success(auditService.listConnectionLogs(query.appId(), query.limit()));
    }

    @PostMapping("/audit/message-log/list")
    @Operation(summary = "报文留存")
    public ApiResponse<List<MessageLogEntity>> messageLogs(@RequestBody AuditQuery query) {
        return ApiResponse.success(auditService.listMessageLogs(query.appId(), query.limit()));
    }

    public record AuditQuery(Long appId, int limit) {
    }
}
