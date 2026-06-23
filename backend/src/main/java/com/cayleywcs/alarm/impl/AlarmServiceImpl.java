package com.cayleywcs.alarm.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.alarm.mapper.AlarmMapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.ReconcileSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmServiceImpl implements AlarmService {
    private final AlarmMapper alarmMapper;

    public AlarmServiceImpl(AlarmMapper alarmMapper) {
        this.alarmMapper = alarmMapper;
    }

    @Override
    @Transactional
    public AlarmEntity raise(Long appId, long faultCode, String level, String message, String suggestion) {
        AlarmEntity active = alarmMapper.selectOne(new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .eq("\"fault_code\"", faultCode)
                .eq("\"status\"", "raised")
                .last("limit 1"));
        if (active != null) {
            return active; // 去重
        }
        LocalDateTime now = LocalDateTime.now();
        AlarmEntity row = new AlarmEntity();
        row.setApp_id(appId);
        row.setFault_code(faultCode);
        row.setLevel(level == null ? "error" : level);
        row.setMessage(message == null ? "" : message);
        row.setSuggestion(suggestion == null ? "" : suggestion);
        row.setStatus("raised");
        row.setRaised_time(now);
        row.setCreator("system");
        row.setCreate_time(now);
        row.setLast_update_time(now);
        row.setIs_valid(true);
        row.setTenant_id(1L);
        alarmMapper.insert(row);
        return row;
    }

    @Override
    @Transactional
    public AlarmEntity ack(Long id, String ackBy) {
        AlarmEntity row = alarmMapper.selectById(id);
        if (row == null || !Boolean.TRUE.equals(row.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "alarm not found: " + id);
        }
        row.setStatus("ack");
        row.setAck_by(ackBy == null ? "" : ackBy);
        row.setAck_time(LocalDateTime.now());
        row.setLast_update_time(LocalDateTime.now());
        alarmMapper.updateById(row);
        return row;
    }

    @Override
    @Transactional
    public boolean clear(Long id) {
        AlarmEntity row = alarmMapper.selectById(id);
        if (row == null || !Boolean.TRUE.equals(row.getIs_valid())) {
            return false;
        }
        row.setStatus("cleared");
        row.setCleared_time(LocalDateTime.now());
        row.setLast_update_time(LocalDateTime.now());
        return alarmMapper.updateById(row) == 1;
    }

    @Override
    @Transactional
    public void clearActiveFaults(Long appId) {
        clearWhere(new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .in("\"status\"", "raised", "ack")
                .gt("\"fault_code\"", 0));
    }

    @Override
    @Transactional
    public void clearFault(Long appId, long faultCode) {
        clearWhere(new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .eq("\"fault_code\"", faultCode)
                .in("\"status\"", "raised", "ack"));
    }

    @Override
    @Transactional
    public void clearCommAlarms(Long appId) {
        clearWhere(new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .in("\"status\"", "raised", "ack")
                .le("\"fault_code\"", 0));
    }

    @Override
    public PageData<AlarmEntity> page(PageSearch pageSearch) {
        List<AlarmEntity> rows = alarmMapper.selectList(new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true)
                .orderByDesc("\"id\""));
        return PageSupport.slice(rows, pageSearch);
    }

    @Override
    public List<AlarmEntity> listActive(Long appId) {
        QueryWrapper<AlarmEntity> wrapper = new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true)
                .in("\"status\"", "raised", "ack")
                .orderByDesc("\"id\"");
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        return alarmMapper.selectList(wrapper);
    }

    @Override
    public List<AlarmEntity> queryReconcile(Long appId, long sinceMillis, int limit) {
        QueryWrapper<AlarmEntity> wrapper = new QueryWrapper<AlarmEntity>().eq("\"is_valid\"", true);
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        if (sinceMillis > 0) {
            wrapper.ge("\"last_update_time\"", ReconcileSupport.toLocalDateTime(sinceMillis));
        }
        wrapper.orderByAsc("\"last_update_time\"").orderByAsc("\"id\"")
                .last("limit " + ReconcileSupport.clampLimit(limit));
        return alarmMapper.selectList(wrapper);
    }

    private void clearWhere(QueryWrapper<AlarmEntity> wrapper) {
        for (AlarmEntity row : alarmMapper.selectList(wrapper)) {
            row.setStatus("cleared");
            row.setCleared_time(LocalDateTime.now());
            row.setLast_update_time(LocalDateTime.now());
            alarmMapper.updateById(row);
        }
    }
}
