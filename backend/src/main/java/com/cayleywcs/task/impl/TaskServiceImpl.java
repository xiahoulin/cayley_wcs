package com.cayleywcs.task.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
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
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import com.cayleywcs.task.orchestrate.TaskSelectionStrategyRegistry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskServiceImpl implements TaskService {
    private final TaskMapper taskMapper;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationService applicationService;
    private final TaskSelectionStrategyRegistry strategyRegistry;

    public TaskServiceImpl(TaskMapper taskMapper, CurrentUserProvider currentUserProvider,
                           ApplicationService applicationService, TaskSelectionStrategyRegistry strategyRegistry) {
        this.taskMapper = taskMapper;
        this.currentUserProvider = currentUserProvider;
        this.applicationService = applicationService;
        this.strategyRegistry = strategyRegistry;
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
        // 链归属只能由编排器物化产生：管理端手动建任务不得注入 chain_id/seq_no 野行干扰编排
        entity.setChain_id(null);
        entity.setSeq_no(null);
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
                    .isNull("\"chain_id\"") // 幂等命名空间隔离：不得误命中链子任务（task_no=chainNo-seq 可能撞名）
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
        t.setPayload(request.payload());
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
        if (TaskStatus.COMPLETED.equals(t.getStatus()) || TaskStatus.EXECUTING.equals(t.getStatus())) {
            throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL, "执行中/已完成任务不可取消: " + id);
        }
        t.setStatus(TaskStatus.CANCELLED);
        Audits.touch(t);
        // 乐观锁：引擎并发推进赢了则 0 行，取消失败由调用方重试（防"取消覆盖执行态"或反向复活）。
        return taskMapper.updateById(t) == 1;
    }

    @Override
    public TaskEntity findNextForApp(Long appId) {
        // 1) 在飞(dispatched/executing)优先继续——设备级单飞下的正确性约束，不受策略/工作模式影响
        TaskEntity inFlight = taskMapper.selectOne(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_id\"", appId)
                .in("\"status\"", TaskStatus.DISPATCHED, TaskStatus.EXECUTING)
                .last("order by \"id\" asc limit 1"));
        if (inFlight != null) {
            return inFlight;
        }
        // 2) 工作模式闸（M10 队列暂停/维护）：非 normal 停新派工，在飞已于上一步放行（graceful）
        ApplicationEntity app = findApp(appId);
        if (app != null && app.getWork_mode() != null && !app.getWork_mode().isBlank()
                && !"normal".equals(app.getWork_mode())) {
            return null;
        }
        // 3) 按应用派工策略选 pending（策略只排序 pending）
        String strategyCode = app == null ? null : app.getDispatch_strategy();
        return strategyRegistry.byCodeOrDefault(strategyCode).nextPending(appId);
    }

    /**
     * 应用新鲜读（work_mode/策略改了立即生效）。仅 NOT_FOUND 容错为 null（测试/孤儿任务）；
     * 其他异常（DB 抖动等）原样抛出——引擎放弃本步下轮重试，**不得 fail-open 绕过暂停闸**（对抗审查 P2）。
     */
    private ApplicationEntity findApp(Long appId) {
        try {
            return applicationService.getById(appId);
        } catch (WcsException ex) {
            if (ex.getCode() == ErrorCode.NOT_FOUND.code()) {
                return null;
            }
            throw ex;
        }
    }

    @Override
    @Transactional
    public boolean reprioritize(Long id, Long priority) {
        TaskEntity t = taskMapper.selectById(id);
        if (t == null || !Boolean.TRUE.equals(t.getIs_valid())) {
            return false;
        }
        if (TaskStatus.COMPLETED.equals(t.getStatus()) || TaskStatus.FAILED.equals(t.getStatus())
                || TaskStatus.CANCELLED.equals(t.getStatus())) {
            throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL, "终态任务不可改优先级: " + id);
        }
        t.setPriority(priority == null ? 0L : priority);
        Audits.touch(t);
        return taskMapper.updateById(t) == 1; // 乐观锁：引擎并发推进赢了则 0 行，调用方重试
    }

    @Override
    @Transactional
    public boolean retryTask(Long id) {
        TaskEntity t = taskMapper.selectById(id);
        if (t == null || !Boolean.TRUE.equals(t.getIs_valid())) {
            return false;
        }
        if (!TaskStatus.FAILED.equals(t.getStatus())) {
            throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL, "仅失败任务可重试: " + id);
        }
        if (t.getChain_id() != null) {
            throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL, "链内任务请走链级处理(段重试由编排器按 failure_policy 自动进行): " + id);
        }
        return doReset(t);
    }

    @Override
    @Transactional
    public boolean resetForRetry(Long id) {
        TaskEntity t = taskMapper.selectById(id);
        if (t == null || !Boolean.TRUE.equals(t.getIs_valid()) || !TaskStatus.FAILED.equals(t.getStatus())) {
            return false; // 并发已推进/不存在：调用方（调度器/编排器）下轮重判
        }
        return doReset(t);
    }

    private boolean doReset(TaskEntity t) {
        t.setStatus(TaskStatus.PENDING);
        t.setHandshake_step(HandshakeStep.CREATED.name());
        t.setError_code(0L);
        t.setRetry_count((t.getRetry_count() == null ? 0 : t.getRetry_count()) + 1);
        // finish_time 残留旧值（MP null 字段不更新），完成/失败时会被覆盖
        Audits.touch(t);
        return taskMapper.updateById(t) == 1;
    }

    @Override
    public List<TaskEntity> queryReconcile(Long appId, long sinceMillis, int limit) {
        QueryWrapper<TaskEntity> wrapper = new QueryWrapper<TaskEntity>().eq("\"is_valid\"", true);
        if (appId != null) {
            wrapper.eq("\"app_id\"", appId);
        }
        return reconcileQuery(wrapper, sinceMillis, limit);
    }

    @Override
    public List<TaskEntity> queryReconcile(List<Long> appIds, long sinceMillis, int limit) {
        if (appIds != null && appIds.isEmpty()) {
            return List.of(); // 无可见应用
        }
        QueryWrapper<TaskEntity> wrapper = new QueryWrapper<TaskEntity>().eq("\"is_valid\"", true);
        if (appIds != null) {
            wrapper.in("\"app_id\"", appIds);
        }
        return reconcileQuery(wrapper, sinceMillis, limit);
    }

    private List<TaskEntity> reconcileQuery(QueryWrapper<TaskEntity> wrapper, long sinceMillis, int limit) {
        if (sinceMillis > 0) {
            wrapper.ge("\"last_update_time\"", ReconcileSupport.toLocalDateTime(sinceMillis));
        }
        wrapper.orderByAsc("\"last_update_time\"").orderByAsc("\"id\"")
                .last("limit " + ReconcileSupport.clampLimit(limit));
        return taskMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public boolean save(TaskEntity entity) {
        Audits.touch(entity);
        return taskMapper.updateById(entity) == 1;
    }

    private static void applyDefaults(TaskEntity t) {
        if (t.getStatus() == null || t.getStatus().isBlank()) {
            t.setStatus(TaskStatus.PENDING);
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
