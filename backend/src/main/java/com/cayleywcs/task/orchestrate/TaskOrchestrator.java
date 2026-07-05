package com.cayleywcs.task.orchestrate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.task.HandshakeStep;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskChainMapper;
import com.cayleywcs.task.mapper.TaskMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 任务链编排器（M10）：无内存态，每 tick 从 DB 派生——段严格串行（段 k 终态后才物化 k+1，无 hold-and-wait 不死锁）。
 *
 * 职责所有权（并发立法）：本类只插子行、只改链行、只消费**已提交终态**子行；从不推进握手（引擎的事）、
 * 从不触碰非终态子行（管理 API 的事）。与管理端取消的互斥经链行乐观锁：**物化前先赢链行版本**（占位提交，
 * 对齐 M8/M9 claim 模式），取消赢了则本段不物化、子任务零插入。
 */
@Component
public class TaskOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);

    /** 编排类报警码：链激活超时（-1 通讯 / -2 未知设备类型 / -3 编排）。 */
    public static final long FAULT_CODE_CHAIN_ACTIVATION = -3L;

    private final TaskChainMapper chainMapper;
    private final TaskMapper taskMapper;
    private final TaskService taskService;
    private final ApplicationService applicationService;
    private final DeviceSelectionStrategyRegistry selectionRegistry;
    private final AlarmService alarmService;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate materializeTx;
    private final long activationTimeoutMs;
    private final long retryBackoffMs;

    public TaskOrchestrator(TaskChainMapper chainMapper, TaskMapper taskMapper,
                            TaskService taskService, ApplicationService applicationService,
                            DeviceSelectionStrategyRegistry selectionRegistry, AlarmService alarmService,
                            CurrentUserProvider currentUserProvider,
                            PlatformTransactionManager transactionManager,
                            @Value("${cayleywcs.chain.activation-timeout-ms:0}") long activationTimeoutMs,
                            @Value("${cayleywcs.task.retry-backoff-ms:30000}") long retryBackoffMs) {
        this.chainMapper = chainMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.applicationService = applicationService;
        this.selectionRegistry = selectionRegistry;
        this.alarmService = alarmService;
        this.currentUserProvider = currentUserProvider;
        // 物化必须原子（对抗审查 P1）：占位 updateById 与子行 insert 若各自 auto-commit，
        // /chain/cancel 可在两次提交之间穿越——链已取消但孤儿子任务照常落库并被引擎执行。
        // @Transactional 对内部自调用失效，故用编程式事务。
        this.materializeTx = new TransactionTemplate(transactionManager);
        this.activationTimeoutMs = activationTimeoutMs;
        this.retryBackoffMs = retryBackoffMs;
    }

    /** fixedDelay 保证本方法不与自身并发（单飞）。 */
    @Scheduled(fixedDelayString = "${cayleywcs.chain.orchestrate-interval-ms:2000}")
    public void tick() {
        runOnce();
    }

    /** 单轮编排（公开供测试驱动/崩溃重放验证）。 */
    public void runOnce() {
        List<TaskChainEntity> active = chainMapper.selectList(new QueryWrapper<TaskChainEntity>()
                .eq("\"is_valid\"", true)
                .in("\"status\"", ChainStatus.PENDING, ChainStatus.RUNNING)
                .orderByAsc("\"id\""));
        for (TaskChainEntity chain : active) {
            try {
                advanceChain(chain);
            } catch (RuntimeException ex) {
                log.warn("chain {} 编排异常，本轮跳过: {}", chain.getChain_no(), ex.getMessage());
            }
        }
    }

    private void advanceChain(TaskChainEntity chain) {
        List<TaskEntity> tasks = taskMapper.selectList(new QueryWrapper<TaskEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"chain_id\"", chain.getId())
                .orderByAsc("\"seq_no\""));

        // 1) 链级终态判定（只消费已提交终态子行）；failed 段先走 failure_policy 自动重试（M11）
        for (TaskEntity t : tasks) {
            if (TaskStatus.FAILED.equals(t.getStatus())) {
                if (tryAutoRetrySegment(chain, t)) {
                    return; // 段已重派（回 pending），链保持 running 等它重跑
                }
                finishChain(chain, ChainStatus.FAILED,
                        "段 " + t.getSeq_no() + " 失败(task " + t.getTask_no() + ", error_code=" + t.getError_code()
                                + ", 重试 " + (t.getRetry_count() == null ? 0 : t.getRetry_count()) + " 次)");
                return;
            }
            if (TaskStatus.CANCELLED.equals(t.getStatus())) {
                finishChain(chain, ChainStatus.CANCELLED, "段 " + t.getSeq_no() + " 被取消(task " + t.getTask_no() + ")");
                return;
            }
        }
        // 2) 有非终态子行在飞：引擎推进中，本轮不动
        if (tasks.stream().anyMatch(t -> !TaskStatus.COMPLETED.equals(t.getStatus()))) {
            return;
        }
        // 3) 已物化段全部完成：找下一段
        long maxSeq = tasks.stream().mapToLong(t -> t.getSeq_no() == null ? 0 : t.getSeq_no()).max().orElse(0);
        Map<String, Object> next = nextSegment(chain, maxSeq);
        if (next == null) {
            finishChain(chain, ChainStatus.COMPLETED, "");
            return;
        }
        // 激活等待基准 = 本段开始等待的时刻（上一段完成时间；首段为链行时间）。
        // 不能只用链行 last_update_time——它停在上一段物化时刻，会把上段执行耗时误算进本段等待（对抗审查 P2）。
        LocalDateTime waitingSince = tasks.stream()
                .map(TaskEntity::getFinish_time)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(chain.getLast_update_time() == null ? chain.getCreate_time() : chain.getLast_update_time());
        materialize(chain, next, waitingSince);
    }

    /**
     * 链段自动重试（M11）：app.failure_policy=auto_retry 且未耗尽（任务级 max_retry>0 优先，否则应用级
     * max_retry）且退避到点 → resetForRetry 段任务（回 pending，引擎重推），链保持 running。
     * 返回 false = 不重试（manual/耗尽/退避未到点由返回 true+不动区分：退避未到点也返回 true 等下轮）。
     */
    private boolean tryAutoRetrySegment(TaskChainEntity chain, TaskEntity t) {
        ApplicationEntity app;
        try {
            app = applicationService.getById(t.getApp_id());
        } catch (com.cayleywcs.common.exception.WcsException ex) {
            if (ex.getCode() == com.cayleywcs.common.exception.ErrorCode.NOT_FOUND.code()) {
                return false; // 应用已删：无重试策略可依，走链 failed
            }
            throw ex; // DB 抖动等异常：上抛由 runOnce 捕获跳过本轮——不得把瞬时故障误判成链终态
        }
        if (!"auto_retry".equals(app.getFailure_policy())) {
            return false;
        }
        long taskMax = t.getMax_retry() == null ? 0 : t.getMax_retry();
        long appMax = app.getMax_retry() == null ? 0 : app.getMax_retry();
        long effectiveMax = taskMax > 0 ? taskMax : appMax;
        long count = t.getRetry_count() == null ? 0 : t.getRetry_count();
        if (count >= effectiveMax) {
            return false; // 耗尽：走链 failed（转人工）
        }
        if (t.getLast_update_time() != null && Duration.between(t.getLast_update_time(), LocalDateTime.now())
                .toMillis() < retryBackoffMs) {
            return true; // 退避未到点：本轮不动（链保持 running），下轮再试
        }
        // 段重派与 /chain/cancel 的互斥（M11 审查 P1，与 materialize 同模式）：同一事务内先赢链行版本再重置
        // 段任务——否则 cancel 的级联查询（只取 pending）可在 reset 提交前跑过，已取消链复活孤儿 pending 子任务。
        materializeTx.executeWithoutResult(txStatus -> {
            Audits.touch(chain);
            if (chainMapper.updateById(chain) != 1) {
                log.warn("chain {} 段重派占位失败(并发写赢，可能已取消)，本轮放弃", chain.getChain_no());
                txStatus.setRollbackOnly();
                return;
            }
            if (taskService.resetForRetry(t.getId())) {
                log.info("chain {} 段 {} 自动重派（第 {} 次重试）", chain.getChain_no(), t.getSeq_no(), count + 1);
            }
        });
        return true; // 已重派或并发变化：均等下轮重判
    }

    private Map<String, Object> nextSegment(TaskChainEntity chain, long afterSeq) {
        List<Map<String, Object>> segs = chain.getSegments();
        if (segs == null) {
            return null;
        }
        return segs.stream()
                .filter(s -> segLong(s, "seq", 0) > afterSeq)
                .min(Comparator.comparingLong(s -> segLong(s, "seq", 0)))
                .orElse(null);
    }

    private void materialize(TaskChainEntity chain, Map<String, Object> seg, LocalDateTime waitingSince) {
        long seq = segLong(seg, "seq", 0);
        // 崩溃重放幂等：先查 (chain_id, seq_no)，已物化则等它跑（引擎推进）；
        // 并发双物化由 V11 唯一索引 uk_wcs_task_chain_seq 兜底（下方 DuplicateKeyException）。
        Long existing = taskMapper.selectCount(new QueryWrapper<TaskEntity>()
                .eq("\"chain_id\"", chain.getId())
                .eq("\"seq_no\"", seq));
        if (existing != null && existing > 0) {
            return;
        }
        // 选设备：显式 appId 直接绑定；候选组走选机策略
        Long appId = segLongOrNull(seg, "appId");
        if (appId == null) {
            List<Long> candidates = segLongList(seg, "candidateAppIds");
            DeviceSelectionStrategy strategy = selectionRegistry.byCodeOrDefault(chain.getSelect_strategy());
            appId = strategy.select(candidates, new DeviceSelectionStrategy.SelectionContext(chain.getId(), seq));
            if (appId == null) {
                handleNoCandidate(chain, seq, candidates, waitingSince);
                return;
            }
        }
        // 占位提交（cancel 竞态防护）：先赢链行版本再插子行——取消赢了本段零插入。
        // 占位与插入在**同一事务**（对抗审查 P1）：事务持有链行锁至提交，并发 cancel 的 updateById
        // 要么等锁后 0 行失败重试（重试可见新子行、级联完整），要么先赢使占位 0 行——不存在孤儿子任务窗口。
        // 崩溃窗口（事务未提交）自愈：整体回滚，下轮重进重新占位+插入。
        Long boundAppId = appId;
        try {
            materializeTx.executeWithoutResult(txStatus -> {
                if (ChainStatus.PENDING.equals(chain.getStatus())) {
                    chain.setStatus(ChainStatus.RUNNING);
                }
                Audits.touch(chain);
                if (chainMapper.updateById(chain) != 1) {
                    log.warn("chain {} 物化占位失败(并发写赢，可能已取消)，本段放弃", chain.getChain_no());
                    txStatus.setRollbackOnly();
                    return;
                }
                taskMapper.insert(buildTask(chain, seg, seq, boundAppId));
                log.info("chain {} 段 {} 已物化 → app {}", chain.getChain_no(), seq, boundAppId);
            });
        } catch (DuplicateKeyException ex) {
            // 唯一索引 (chain_id, seq_no) 兜底：另一编排线程（如测试手动 runOnce 与自动 tick 并发）已物化，良性放弃
            log.warn("chain {} 段 {} 并发物化冲突(唯一索引兜底)，本段放弃", chain.getChain_no(), seq);
        }
    }

    private void handleNoCandidate(TaskChainEntity chain, long seq, List<Long> candidates, LocalDateTime waitingSince) {
        if (activationTimeoutMs <= 0) {
            return; // 超时关闭：无限等待候选可用
        }
        if (waitingSince == null
                || Duration.between(waitingSince, LocalDateTime.now()).toMillis() < activationTimeoutMs) {
            return; // 本段等待未超时，继续等
        }
        String msg = "链 " + chain.getChain_no() + " 段 " + seq + " 激活超时：候选设备均不可用 " + candidates;
        // 先落终态再报警（对抗审查 P3：反序会在并发 cancel 赢版本时留下孤立报警）
        if (!finishChain(chain, ChainStatus.FAILED, msg)) {
            return;
        }
        if (candidates != null && !candidates.isEmpty()) {
            // 报警挂候选首个设备（报警表 app_id 非空约束；编排类码 -3）
            alarmService.raise(candidates.get(0), FAULT_CODE_CHAIN_ACTIVATION, "error", msg, "检查候选设备连接/工作模式");
        }
    }

    private TaskEntity buildTask(TaskChainEntity chain, Map<String, Object> seg, long seq, Long appId) {
        TaskEntity t = new TaskEntity();
        t.setApp_id(appId);
        t.setTask_no(chain.getChain_no() + "-" + seq); // 确定性任务号：先查后插天然幂等
        t.setChain_id(chain.getId());
        t.setSeq_no(seq);
        t.setTask_type(segString(seg, "taskType", ""));
        t.setTake_row(segLongOrNull(seg, "takeRow"));
        t.setTake_column(segLongOrNull(seg, "takeColumn"));
        t.setTake_floor(segLongOrNull(seg, "takeFloor"));
        t.setPut_row(segLongOrNull(seg, "putRow"));
        t.setPut_column(segLongOrNull(seg, "putColumn"));
        t.setPut_floor(segLongOrNull(seg, "putFloor"));
        t.setPort_num(segLongOrNull(seg, "portNum"));
        t.setPayload(segMap(seg, "payload"));
        t.setPriority(chain.getPriority() == null ? 0L : chain.getPriority());
        t.setWms_ref(chain.getWms_ref());
        t.setStatus(TaskStatus.PENDING);
        t.setHandshake_step(HandshakeStep.CREATED.name());
        t.setError_code(0L);
        Audits.fillCreate(t, currentUserProvider.systemUser());
        return t;
    }

    /** 链落终态。返回 false=并发写赢（如已取消），下轮以最新链态重判。 */
    private boolean finishChain(TaskChainEntity chain, String status, String errorMessage) {
        if (status.equals(chain.getStatus())) {
            return false;
        }
        chain.setStatus(status);
        chain.setError_message(errorMessage == null ? "" : errorMessage);
        chain.setFinish_time(LocalDateTime.now());
        Audits.touch(chain);
        if (chainMapper.updateById(chain) != 1) {
            log.warn("chain {} 终态落库冲突(并发写赢)，下轮以最新链态重判", chain.getChain_no());
            return false;
        }
        if (!ChainStatus.COMPLETED.equals(status)) {
            log.warn("chain {} → {}: {}", chain.getChain_no(), status, errorMessage);
        }
        return true;
    }

    // ===== segments Map 取值（Jackson 反序列化数字可能是 Integer/Long）=====
    private static long segLong(Map<String, Object> seg, String key, long def) {
        Object v = seg.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    private static Long segLongOrNull(Map<String, Object> seg, String key) {
        Object v = seg.get(key);
        return v instanceof Number n ? n.longValue() : null;
    }

    private static String segString(Map<String, Object> seg, String key, String def) {
        Object v = seg.get(key);
        return v == null ? def : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> segMap(Map<String, Object> seg, String key) {
        Object v = seg.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static List<Long> segLongList(Map<String, Object> seg, String key) {
        Object v = seg.get(key);
        List<Long> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    out.add(n.longValue());
                }
            }
        }
        return out;
    }
}
