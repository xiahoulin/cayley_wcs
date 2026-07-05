package com.cayleywcs.task.orchestrate;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 轮询选机：(chainId + seq) 对候选数取模，完全无状态（多实例/重启后决定性一致）。
 * 不感知设备可用性——选中设备未连接时任务在其队列排队等设备上线（引擎只推 RUNNING 连接）。
 */
@Component
public class RoundRobinStrategy implements DeviceSelectionStrategy {

    @Override
    public String code() {
        return "round_robin";
    }

    @Override
    public Long select(List<Long> candidateAppIds, SelectionContext ctx) {
        if (candidateAppIds == null || candidateAppIds.isEmpty()) {
            return null;
        }
        long chainId = ctx.chainId() == null ? 0 : ctx.chainId();
        long seq = ctx.seq() == null ? 0 : ctx.seq();
        int idx = (int) Math.floorMod(chainId + seq, candidateAppIds.size());
        return candidateAppIds.get(idx);
    }
}
