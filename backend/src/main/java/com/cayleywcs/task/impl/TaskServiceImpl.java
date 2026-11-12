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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import com.cayleywcs.task.RequestFingerprints;
import com.cayleywcs.task.TaskExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskServiceImpl implements TaskService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private final TaskMapper taskMapper;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationService applicationService;
    private final TaskSelectionStrategyRegistry strategyRegistry;
    private TaskExecutionService execution;

    @Autowired
    void setExecution(TaskExecutionService execution) { this.execution = execution; }

    public TaskServiceImpl(TaskMapper taskMapper, CurrentUserProvider currentUserProvider,
                           ApplicationService applicationService, TaskSelectionStrategyRegistry strategyRegistry) {
        this.taskMapper = taskMapper;
        this.currentUserProvider = currentUserProvider;
        this.applicationService = applicationService;
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageData<TaskEntity> page(PageSearch pageSearch) {
        return PageSupport.database(pageSearch,
                () -> taskMapper.selectCount(visibleQuery()),
                window -> taskMapper.selectList(visibleQuery()
                        .orderByDesc("\"id\"")
                        .last("limit " + window.size() + " offset " + window.offset())));
    }

    @Override
    public TaskEntity getById(Long id) {
        TaskEntity t = taskMapper.selectById(id);
        if (t == null || !Boolean.TRUE.equals(t.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "task not found: " + id);
        }
        requireVisible(t);
        return t;
    }

    private QueryWrapper<TaskEntity> visibleQuery() {
        var query=new QueryWrapper<TaskEntity>().eq("\"is_valid\"",true);
        var user=currentUserProvider.systemUser();
        if (!(Long.valueOf(0).equals(user.userId()) && "system".equals(user.userRole()))) query.eq("\"tenant_id\"",user.tenantId());
        return query;
    }
    private void requireVisible(TaskEntity task) {
        var user=currentUserProvider.systemUser();
        if (!(Long.valueOf(0).equals(user.userId()) && "system".equals(user.userRole())) && !Objects.equals(user.tenantId(),task.getTenant_id()))
            throw new WcsException(ErrorCode.NOT_FOUND,"任务不存在或不可访问");
    }

    @Override
    @Transactional
    public TaskEntity create(TaskEntity entity) {
        if(lifecycle!=null) lifecycle.references();
        // 链归属只能由编排器物化产生：管理端手动建任务不得注入 chain_id/seq_no 野行干扰编排
        entity.setChain_id(null);
        entity.setSeq_no(null);
        applyDefaults(entity);
        Audits.fillCreate(entity, currentUserProvider.systemUser());
        if (execution != null) execution.initialize(entity, 0L);
        taskMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public TaskEntity dispatch(DispatchRequest request) {
        return dispatch(request, 0L);
    }

    @Override
    @Transactional
    public TaskEntity dispatch(DispatchRequest request, Long callerAppId) {
        if(lifecycle!=null) lifecycle.references();
        if (request.appId() == null) {
            throw new WcsException(ErrorCode.TASK_INVALID, "appId 必填");
        }
        if (request.taskNo() == null || request.taskNo().isBlank() || request.taskNo().length() > 128)
            throw new WcsException(ErrorCode.TASK_INVALID, "taskNo 必填且不超过128字符");
        if (callerAppId == null) throw new WcsException(ErrorCode.FORBIDDEN, "缺少可信调用方");
        Map<String,Object> normalized=execution==null?request.payload():execution.normalize(request.appId(),request.payload());
        Map<String,Object> content = new LinkedHashMap<>();
        content.put("payload",normalized);
        content.put("priority",request.priority() == null ? 0L : request.priority());
        content.put("wmsRef",request.wmsRef() == null ? "" : request.wmsRef());
        String fingerprint = RequestFingerprints.of(content);
        if (request.taskNo() != null && !request.taskNo().isBlank()) {
            TaskEntity existing = taskMapper.selectOne(new QueryWrapper<TaskEntity>()
                    .eq("\"is_valid\"", true)
                    .eq("\"app_id\"", request.appId())
                    .eq("\"task_no\"", request.taskNo())
                    .isNull("\"chain_id\"") // 幂等命名空间隔离：不得误命中链子任务（task_no=chainNo-seq 可能撞名）
                    .last("limit 1"));
            if (existing != null) {
                if (!Objects.equals(existing.getCaller_app_id(), callerAppId)
                        || !Objects.equals(existing.getRequest_fingerprint(), fingerprint))
                    throw new WcsException(ErrorCode.CONFLICT,"任务编号已使用或请求内容不同");
                return existing; // 幂等
            }
        }
        TaskEntity t = new TaskEntity();
        t.setApp_id(request.appId());
        t.setTask_no(request.taskNo());
        t.setPriority(request.priority());
        t.setWms_ref(request.wmsRef()==null?"":request.wmsRef());
        t.setPayload(normalized);
        t.setRequest_fingerprint(fingerprint);
        t.setIdempotency_scope("single");
        applyDefaults(t);
        Audits.fillCreate(t, currentUserProvider.systemUser());
        if (execution != null) execution.initialize(t, callerAppId);
        try { taskMapper.insert(t); }
        catch (DuplicateKeyException ex) { throw new WcsException(ErrorCode.CONFLICT, "任务编号并发重复，请按原请求查询重试"); }
        return t;
    }

    @Override
    public boolean cancel(Long id) {
        TaskEntity found=getById(id);
        return execution==null?cancelLocked(id):execution.exclusiveTransaction(found.getApp_id(),()->cancelLocked(id));
    }

    private boolean cancelLocked(Long id) {
        TaskEntity t = taskMapper.selectById(id);
        if (t == null || !Boolean.TRUE.equals(t.getIs_valid())) {
            return false;
        }
        if (TaskStatus.COMPLETED.equals(t.getStatus()) || TaskStatus.EXECUTING.equals(t.getStatus()) || Boolean.TRUE.equals(t.getPhysical_started())) {
            throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL, "执行中/已完成任务不可取消: " + id);
        }
        if (execution!=null) execution.cleanupUnstarted(t);
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
     * 其他异常（DB 抖动等）原样抛出——引擎放弃本步下轮重试，**不得 fail-open 绕过暂停闸**。
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
        TaskEntity t = getById(id);
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
        TaskEntity t = getById(id);
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
        if (execution != null) execution.assertRetryAllowed(t);
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
        if (t.getRetry_count()==null) t.setRetry_count(0L);
        if (t.getMax_retry()==null) t.setMax_retry(0L);
        if (t.getVersion()==null) t.setVersion(0L);
        if (t.getPhysical_started()==null) t.setPhysical_started(false);
    }
}
