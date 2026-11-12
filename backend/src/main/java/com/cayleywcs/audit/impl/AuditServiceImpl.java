package com.cayleywcs.audit.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.audit.AuditService;
import com.cayleywcs.audit.entity.ConnectionLogEntity;
import com.cayleywcs.audit.entity.MessageLogEntity;
import com.cayleywcs.audit.mapper.ConnectionLogMapper;
import com.cayleywcs.audit.mapper.MessageLogMapper;
import com.cayleywcs.config.WcsMetrics;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditServiceImpl implements AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final ConnectionLogMapper connectionLogMapper;
    private final MessageLogMapper messageLogMapper;
    @Autowired private WcsMetrics metrics;
    @Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AuditServiceImpl(ConnectionLogMapper connectionLogMapper, MessageLogMapper messageLogMapper) {
        this.connectionLogMapper = connectionLogMapper;
        this.messageLogMapper = messageLogMapper;
    }

    @Override
    public void writeConnectionLog(Long appId, String appCode, String event, String state, String detail) {
        try {
            ConnectionLogEntity row = new ConnectionLogEntity();
            row.setApp_id(appId);
            row.setApp_code(appCode == null ? "" : appCode);
            row.setEvent(event == null ? "" : event);
            row.setState(state == null ? "" : state);
            row.setDetail(truncate(detail, 2048));
            row.setCreate_time(LocalDateTime.now());
            row.setTenant_id(1L);
            persistIfApplicationExists(appId, () -> connectionLogMapper.insert(row));
        } catch (RuntimeException ex) {
            long count = metrics == null ? 1 : metrics.auditFailure("connection");
            if (WcsMetrics.shouldLogFailure(count)) {
                log.warn("connection audit write failed (count={}, appId={}, cause={})",
                        count, appId, ex.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void writeMessageLog(Long appId, String direction, String fieldName, String address, String rawValue, String jsonPayload) {
        try {
            MessageLogEntity row = new MessageLogEntity();
            row.setApp_id(appId);
            row.setDirection(direction == null ? "" : direction);
            row.setField_name(fieldName == null ? "" : fieldName);
            row.setAddress(address == null ? "" : address);
            row.setRaw_value(truncate(rawValue, 512));
            row.setJson_payload(jsonPayload == null ? "{}" : jsonPayload);
            row.setCreate_time(LocalDateTime.now());
            row.setTenant_id(1L);
            persistIfApplicationExists(appId, () -> messageLogMapper.insert(row));
        } catch (RuntimeException ex) {
            long count = metrics == null ? 1 : metrics.auditFailure("message");
            if (WcsMetrics.shouldLogFailure(count)) {
                log.warn("message audit write failed (count={}, appId={}, cause={})",
                        count, appId, ex.getClass().getSimpleName());
            }
        }
    }

    @Override
    public List<ConnectionLogEntity> listConnectionLogs(Long appId, int limit) {
        QueryWrapper<ConnectionLogEntity> wrapper = new QueryWrapper<ConnectionLogEntity>()
                .orderByDesc("\"id\"").last("limit " + clampLimit(limit));
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        return connectionLogMapper.selectList(wrapper);
    }

    @Override
    public List<MessageLogEntity> listMessageLogs(Long appId, int limit) {
        QueryWrapper<MessageLogEntity> wrapper = new QueryWrapper<MessageLogEntity>()
                .orderByDesc("\"id\"").last("limit " + clampLimit(limit));
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        return messageLogMapper.selectList(wrapper);
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return 200;
        }
        return Math.min(limit, 2000);
    }

    private void persistIfApplicationExists(Long appId, Runnable write) {
        if (lifecycle == null) { write.run(); return; }
        lifecycle.referenceWrite(() -> {
            if (jdbc.queryForObject("select count(*) from wcs_application where id=?", Long.class, appId) > 0)
                write.run();
            return null;
        });
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
