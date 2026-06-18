package com.cayleywcs.audit;

import com.cayleywcs.audit.entity.ConnectionLogEntity;
import com.cayleywcs.audit.entity.MessageLogEntity;
import java.util.List;

public interface AuditService {

    void writeConnectionLog(Long appId, String appCode, String event, String state, String detail);

    void writeMessageLog(Long appId, String direction, String fieldName, String address, String rawValue, String jsonPayload);

    List<ConnectionLogEntity> listConnectionLogs(Long appId, int limit);

    List<MessageLogEntity> listMessageLogs(Long appId, int limit);
}
