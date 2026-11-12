package com.cayleywcs.task.orchestrate;

import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.List;
import java.util.Map;

/**
 * 任务链服务（M10 一对多/多对多编排）：链下发（chain_no 幂等）、查询、级联取消。
 * 段推进/物化由 {@link TaskOrchestrator} 负责；本服务不推进握手。
 */
public interface ChainService {

    /**
     * 下发任务链（幂等：同租户同 chain_no 重发返回既有链）。
     * upstreamAppId=AppKey 验签得到的下发者身份（appkey 关闭旁路/管理端手动为 0）。
     */
    TaskChainEntity dispatchChain(ChainDispatchRequest request, Long upstreamAppId);

    PageData<TaskChainEntity> page(PageSearch pageSearch);

    TaskChainEntity getById(Long id);

    /** 链的已物化子任务（按段号升序）。 */
    List<TaskEntity> tasksOf(Long chainId);

    /**
     * 级联取消：链置 cancelled（乐观锁）→ pending 子任务置 cancelled（逐行乐观锁）；
     * 在飞(dispatched/executing)子任务不硬停（设备侧不保证），跑完后编排器不再物化下段。
     * false=链已终态或并发冲突（重试）。
     */
    boolean cancel(Long id);

    /** 对账（/open/chain/query）：按下发者收敛可见范围；upstreamAppId=null 不限（appkey 关闭旁路）。 */
    List<TaskChainEntity> queryReconcile(Long upstreamAppId, long sinceMillis, int limit);

    /** 链下发请求：segments 至少一段，每段 appId 与 candidateAppIds 二选一；seq 缺省按数组序 1..n。 */
    record ChainDispatchRequest(
            String chainNo, Long priority, String selectStrategy, String wmsRef,
            Map<String, Object> payload, List<ChainSegment> segments
    ) {
    }

    /** 链段定义：坐标字段供堆垛机类设备，其他设备参数走 payload（与单任务 DispatchRequest 对齐）。 */
    record ChainSegment(
            Long seq, Long appId, List<Long> candidateAppIds, Map<String, Object> payload
    ) {
        public ChainSegment(Long seq,Long appId,List<Long> candidates,String type,Long tr,Long tc,Long tf,
                            Long pr,Long pc,Long pf,Long port,Map<String,Object> payload) {
            this(seq,appId,candidates,com.cayleywcs.task.RequestPayloads.migrate(type,tr,tc,tf,pr,pc,pf,port,payload));
        }
    }
}
