package com.cayleywcs.task.orchestrate.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.common.support.ReconcileSupport;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskChainMapper;
import com.cayleywcs.task.mapper.TaskMapper;
import com.cayleywcs.task.orchestrate.ChainService;
import com.cayleywcs.task.orchestrate.ChainStatus;
import com.cayleywcs.task.orchestrate.DeviceSelectionStrategyRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.cayleywcs.task.TaskExecutionService;
import com.cayleywcs.task.RequestFingerprints;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Objects;

@Service
public class ChainServiceImpl implements ChainService {
    @Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private static final Logger log = LoggerFactory.getLogger(ChainServiceImpl.class);

    /** chainNo 长度上限：子任务 task_no = chainNo + "-" + seq 须容于 varchar(128)。 */
    private static final int CHAIN_NO_MAX_LEN = 100;

    private final TaskChainMapper chainMapper;
    private final TaskMapper taskMapper;
    private final CurrentUserProvider currentUserProvider;
    private final DeviceSelectionStrategyRegistry selectionRegistry;
    @Autowired private TaskExecutionService execution;
    @Autowired @org.springframework.context.annotation.Lazy private com.cayleywcs.task.TaskService taskService;

    public ChainServiceImpl(TaskChainMapper chainMapper, TaskMapper taskMapper,
                            CurrentUserProvider currentUserProvider,
                            DeviceSelectionStrategyRegistry selectionRegistry) {
        this.chainMapper = chainMapper;
        this.taskMapper = taskMapper;
        this.currentUserProvider = currentUserProvider;
        this.selectionRegistry = selectionRegistry;
    }

    // Catch duplicate insertion only after its transaction has rolled back (PostgreSQL 25P02).
    @Override
    public TaskChainEntity dispatchChain(ChainDispatchRequest request, Long upstreamAppId) {
        try { return lifecycle.referenceWrite(() -> dispatchChainLocked(request,upstreamAppId)); }
        catch (DuplicateKeyException ex) {
            TaskChainEntity winner=findByChainNo(request.chainNo());
            if(winner!=null) {
                String fingerprint=chainFingerprint(request,normalizeSegments(request.segments()));
                if(Objects.equals(winner.getUpstream_app_id(),upstreamAppId) && Objects.equals(winner.getRequest_fingerprint(),fingerprint)) return winner;
                throw new WcsException(ErrorCode.CONFLICT,"链编号并发重复或请求内容不同");
            }
            throw ex;
        }
    }

    private TaskChainEntity dispatchChainLocked(ChainDispatchRequest request,Long upstreamAppId) {
        if (request.chainNo() == null || request.chainNo().isBlank()) {
            throw new WcsException(ErrorCode.TASK_INVALID, "chainNo 必填（链幂等键）");
        }
        if (request.chainNo().length() > CHAIN_NO_MAX_LEN) {
            throw new WcsException(ErrorCode.TASK_INVALID,
                    "chainNo 过长(≤" + CHAIN_NO_MAX_LEN + ")：子任务号 chainNo-seq 需容于 128 字符");
        }
        if (request.selectStrategy() != null && !request.selectStrategy().isBlank()
                && !selectionRegistry.supports(request.selectStrategy())) {
            throw new WcsException(ErrorCode.TASK_INVALID, "未知选机策略: " + request.selectStrategy());
        }
        List<Map<String, Object>> segments = normalizeSegments(request.segments());
        if (upstreamAppId==null) throw new WcsException(ErrorCode.FORBIDDEN,"缺少可信调用方");
        String fingerprint=chainFingerprint(request,segments);
        for (Map<String,Object> segment:segments) {
            List<Long> ids=segment.containsKey("appId")?List.of(((Number)segment.get("appId")).longValue())
                    :(List<Long>)segment.get("candidateAppIds");
            Map<String,Long> revisions=new LinkedHashMap<>();
            Map<String,Object> normalized=null;
            for (Long id:ids) {
                execution.assertCandidate(upstreamAppId,id);
                Map<String,Object> values=execution.normalize(id,(Map<String,Object>)segment.get("payload"));
                if (normalized!=null && !RequestFingerprints.of(normalized).equals(RequestFingerprints.of(values)))
                    throw new WcsException(4223,"候选设备点位约定不同");
                normalized=values; revisions.put(id.toString(),execution.revisionId(id));
            }
            segment.put("payload",normalized); segment.put("pinnedRevisionIds",revisions);
        }

        // 幂等：同租户同 chain_no（唯一索引含软删行——软删链 chain_no 不复用，命中即返回既有）
        TaskChainEntity existing = findByChainNo(request.chainNo());
        if (existing != null) {
            if (!Objects.equals(existing.getUpstream_app_id(),upstreamAppId) || !Objects.equals(existing.getRequest_fingerprint(),fingerprint))
                throw new WcsException(ErrorCode.CONFLICT,"链编号已使用或请求内容不同");
            return existing;
        }
        TaskChainEntity chain = new TaskChainEntity();
        chain.setChain_no(request.chainNo());
        chain.setUpstream_app_id(upstreamAppId == null ? 0L : upstreamAppId);
        chain.setStatus(ChainStatus.PENDING);
        chain.setSegments(segments);
        chain.setRequest_fingerprint(fingerprint);
        chain.setSelect_strategy(request.selectStrategy() == null || request.selectStrategy().isBlank()
                ? "idle_first" : request.selectStrategy());
        chain.setPriority(request.priority() == null ? 0L : request.priority());
        chain.setPayload(request.payload());
        chain.setWms_ref(request.wmsRef() == null ? "" : request.wmsRef());
        chain.setError_message("");
        Audits.fillCreate(chain, currentUserProvider.systemUser());
        chainMapper.insert(chain);
        return chain;
    }

    private String chainFingerprint(ChainDispatchRequest request,List<Map<String,Object>> segments) {
        Map<String,Object> original=new LinkedHashMap<>();
        original.put("segments",segments); original.put("priority",request.priority()==null?0L:request.priority());
        original.put("selectStrategy",request.selectStrategy()==null?"idle_first":request.selectStrategy());
        original.put("wmsRef",request.wmsRef()==null?"":request.wmsRef()); original.put("payload",request.payload());
        return RequestFingerprints.of(original);
    }

    /** 段规范化：seq 缺省按数组序 1..n；校验非空、appId/candidateAppIds 二选一、seq 唯一。 */
    private List<Map<String, Object>> normalizeSegments(List<ChainSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new WcsException(ErrorCode.TASK_INVALID, "segments 至少一段");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> seqSeen = new HashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            ChainSegment seg = segments.get(i);
            // 候选清洗：去 null / 去重（保持顺序）
            List<Long> candidates = seg.candidateAppIds() == null ? List.of()
                    : seg.candidateAppIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
            boolean hasApp = seg.appId() != null;
            boolean hasCandidates = !candidates.isEmpty();
            if (hasApp == hasCandidates) { // 都无或都有
                throw new WcsException(ErrorCode.TASK_INVALID,
                        "段 " + (i + 1) + "：appId 与 candidateAppIds 必须二选一");
            }
            long seq = seg.seq() == null ? i + 1 : seg.seq();
            if (seq <= 0 || !seqSeen.add(seq)) {
                throw new WcsException(ErrorCode.TASK_INVALID, "段号非法或重复: " + seq);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            if (hasApp) {
                m.put("appId", seg.appId());
            } else {
                m.put("candidateAppIds", candidates);
            }
            putIfNotNull(m, "payload", seg.payload());
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(((Number) a.get("seq")).longValue(), ((Number) b.get("seq")).longValue()));
        return out;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object v) {
        if (v != null) {
            m.put(key, v);
        }
    }

    private TaskChainEntity findByChainNo(String chainNo) {
        return chainMapper.selectOne(new QueryWrapper<TaskChainEntity>()
                .eq("\"chain_no\"", chainNo)
                .eq("\"tenant_id\"", currentUserProvider.systemUser().tenantId())
                .last("limit 1"));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageData<TaskChainEntity> page(PageSearch pageSearch) {
        return PageSupport.database(pageSearch,
                () -> chainMapper.selectCount(new QueryWrapper<TaskChainEntity>().eq("\"is_valid\"", true)),
                window -> chainMapper.selectList(new QueryWrapper<TaskChainEntity>()
                        .eq("\"is_valid\"", true)
                        .orderByDesc("\"id\"")
                        .last("limit " + window.size() + " offset " + window.offset())));
    }

    @Override
    public TaskChainEntity getById(Long id) {
        TaskChainEntity chain = chainMapper.selectById(id);
        if (chain == null || !Boolean.TRUE.equals(chain.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "chain not found: " + id);
        }
        return chain;
    }

    @Override
    public List<TaskEntity> tasksOf(Long chainId) {
        return taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"chain_id\"", chainId)
                .orderByAsc("\"seq_no\""));
    }

    @Override
    @Transactional
    public boolean cancel(Long id) {
        TaskChainEntity chain = getById(id);
        if (ChainStatus.terminal(chain.getStatus())) {
            return false;
        }
        // 先赢链行版本（与编排器物化占位互斥）：取消赢了编排器就不可能再物化新段
        chain.setStatus(ChainStatus.CANCELLED);
        chain.setError_message("人工取消");
        chain.setFinish_time(LocalDateTime.now());
        Audits.touch(chain);
        if (chainMapper.updateById(chain) != 1) {
            return false; // 编排器并发推进赢了：调用方重试（下一轮链态已刷新）
        }
        // 级联：pending 子任务置 cancelled（乐观锁逐行；引擎并发赢的行跳过，其在飞化后由取消语义兜底）
        List<TaskEntity> tasks = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"chain_id\"", id)
                .eq("\"status\"", TaskStatus.PENDING));
        for (TaskEntity t : tasks) {
            // 已有物理受理意图的段继续安全收尾；其余段沿单任务路径清理预约号及已写参数。
            if (Boolean.TRUE.equals(t.getPhysical_started())) continue;
            if (!taskService.cancel(t.getId())) {
                log.warn("chain {} 子任务 {} 取消冲突(引擎并发)，该段在飞跑完后链保持 cancelled", id, t.getTask_no());
            }
        }
        return true;
    }

    @Override
    public List<TaskChainEntity> queryReconcile(Long upstreamAppId, long sinceMillis, int limit) {
        QueryWrapper<TaskChainEntity> wrapper = new QueryWrapper<TaskChainEntity>().eq("\"is_valid\"", true);
        if (upstreamAppId != null) {
            wrapper.eq("\"upstream_app_id\"", upstreamAppId);
        }
        if (sinceMillis > 0) {
            wrapper.ge("\"last_update_time\"", ReconcileSupport.toLocalDateTime(sinceMillis));
        }
        wrapper.orderByAsc("\"last_update_time\"").orderByAsc("\"id\"")
                .last("limit " + ReconcileSupport.clampLimit(limit));
        return chainMapper.selectList(wrapper);
    }
}
