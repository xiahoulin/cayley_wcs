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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmServiceImpl implements AlarmService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final AlarmMapper alarmMapper;

    public AlarmServiceImpl(AlarmMapper alarmMapper) {
        this.alarmMapper = alarmMapper;
    }

    @Override
    @Transactional
    public AlarmEntity raise(Long appId, long faultCode, String level, String message, String suggestion) {
        if (lifecycle != null) {
            lifecycle.references();
            if (jdbc.queryForObject("select count(*) from wcs_application where id=?", Long.class, appId) == 0)
                return null; // A delayed device event must not recreate an alarm after application deletion.
        }
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
        // 只清通讯类(-1)：-2 配置/-3 编排/-4 任务停滞是任务/配置侧问题，并不随连接恢复而解决，
        // 若一并清除会把停滞死锁等状态变成完全静默。
        clearWhere(new QueryWrapper<AlarmEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .in("\"status\"", "raised", "ack")
                .eq("\"fault_code\"", -1L));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageData<AlarmEntity> page(PageSearch pageSearch) {
        return PageSupport.database(pageSearch,
                () -> alarmMapper.selectCount(new QueryWrapper<AlarmEntity>().eq("\"is_valid\"", true)),
                window -> alarmMapper.selectList(new QueryWrapper<AlarmEntity>()
                        .eq("\"is_valid\"", true)
                        .orderByDesc("\"id\"")
                        .last("limit " + window.size() + " offset " + window.offset())));
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
        return reconcileQuery(wrapper, sinceMillis, limit);
    }

    @Override
    public List<AlarmEntity> queryReconcile(List<Long> appIds, long sinceMillis, int limit) {
        if (appIds != null && appIds.isEmpty()) {
            return List.of(); // 无可见应用
        }
        QueryWrapper<AlarmEntity> wrapper = new QueryWrapper<AlarmEntity>().eq("\"is_valid\"", true);
        if (appIds != null) {
            wrapper.in("\"app_id\"", appIds);
        }
        return reconcileQuery(wrapper, sinceMillis, limit);
    }

    private List<AlarmEntity> reconcileQuery(QueryWrapper<AlarmEntity> wrapper, long sinceMillis, int limit) {
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
