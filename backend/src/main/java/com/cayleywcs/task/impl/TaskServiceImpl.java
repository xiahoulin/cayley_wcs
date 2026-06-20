package com.cayleywcs.task.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.common.support.ReconcileSupport;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.task.HandshakeStep;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskServiceImpl implements TaskService {
    private final TaskMapper taskMapper;
    private final CurrentUserProvider currentUserProvider;

    public TaskServiceImpl(TaskMapper taskMapper, CurrentUserProvider currentUserProvider) {
        this.taskMapper = taskMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public PageData<TaskEntity> page(PageSearch pageSearch) {
        List<TaskEntity> rows = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .orderByDesc("\"id\""));
        return PageSupport.slice(rows, pageSearch);
    }

    @Override
    public TaskEntity getById(Long id) {
        TaskEntity t = taskMapper.selectById(id);
        if (t == null || !Boolean.TRUE.equals(t.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "task not found: " + id);
        }
        return t;
    }

    @Override
    @Transactional
    public TaskEntity create(TaskEntity entity) {
        applyDefaults(entity);
        Audits.fillCreate(entity, currentUserProvider.systemUser());
        taskMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public TaskEntity dispatch(DispatchRequest request) {
        if (request.appId() == null) {
            throw new WcsException(ErrorCode.TASK_INVALID, "appId 必填");
        }
        if (request.taskNo() != null && !request.taskNo().isBlank()) {
            TaskEntity existing = taskMapper.selectOne(new QueryWrapper<TaskEntity>()
                    .eq("\"is_valid\"", true)
                    .eq("\"app_id\"", request.appId())
                    .eq("\"task_no\"", request.taskNo())
                    .last("limit 1"));
            if (existing != null) {
                return existing; // 幂等
            }
        }
        TaskEntity t = new TaskEntity();
        t.setApp_id(request.appId());
        t.setTask_no(request.taskNo());
        t.setTask_type(request.taskType());
        t.setTake_row(request.takeRow());
        t.setTake_column(request.takeColumn());
        t.setTake_floor(request.takeFloor());
        t.setPut_row(request.putRow());
        t.setPut_column(request.putColumn());
        t.setPut_floor(request.putFloor());
        t.setPort_num(request.portNum());
        t.setPriority(request.priority());
        t.setWms_ref(request.wmsRef());
        applyDefaults(t);
        Audits.fillCreate(t, currentUserProvider.systemUser());
        taskMapper.insert(t);
        return t;
    }

    @Override
    @Transactional
    public boolean cancel(Long id) {
        TaskEntity t = taskMapper.selectById(id);
        if (t == null || !Boolean.TRUE.equals(t.getIs_valid())) {
            return false;
        }
        if ("completed".equals(t.getStatus()) || "executing".equals(t.getStatus())) {
            throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL, "执行中/已完成任务不可取消: " + id);
        }
        t.setStatus("cancelled");
        Audits.touch(t);
        return taskMapper.updateById(t) == 1;
    }

    @Override
    public TaskEntity findNextForApp(Long appId) {
        // 在飞(dispatched/executing)优先继续；否则取优先级最高的 pending。
        return taskMapper.selectOne(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .in("\"status\"", "pending", "dispatched", "executing")
                .last("order by case when \"status\" = 'pending' then 1 else 0 end asc, \"priority\" desc, \"id\" asc limit 1"));
    }

    @Override
    public List<TaskEntity> queryReconcile(Long appId, long sinceMillis, int limit) {
        QueryWrapper<TaskEntity> wrapper = new QueryWrapper<TaskEntity>().eq("\"is_valid\"", true);
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        if (sinceMillis > 0) {
            wrapper.ge("\"last_update_time\"", ReconcileSupport.toLocalDateTime(sinceMillis));
        }
        wrapper.orderByAsc("\"last_update_time\"").orderByAsc("\"id\"")
                .last("limit " + ReconcileSupport.clampLimit(limit));
        return taskMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public void save(TaskEntity entity) {
        Audits.touch(entity);
        taskMapper.updateById(entity);
    }

    private static void applyDefaults(TaskEntity t) {
        if (t.getStatus() == null || t.getStatus().isBlank()) {
            t.setStatus("pending");
        }
        if (t.getHandshake_step() == null || t.getHandshake_step().isBlank()) {
            t.setHandshake_step(HandshakeStep.CREATED.name());
        }
        if (t.getPriority() == null) {
            t.setPriority(0L);
        }
        if (t.getError_code() == null) {
            t.setError_code(0L);
        }
    }
}
