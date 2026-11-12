package com.cayleywcs.task.orchestrate;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.openapi.OpenAccessGuard;
import com.cayleywcs.task.entity.TaskChainEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.cayleywcs.task.RequestPayloads;
import com.cayleywcs.openapi.OpenPublicViews;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放接口：任务链下发与对账（AppKey 鉴权，/open/**）。
 * 越权防护：dispatch 对**每段**的 appId 或全部候选设备逐一校验绑定授权（一段越权整链拒绝）；
 * query 按下发者身份收敛（只见自己下发的链）。单任务 /open/task/dispatch 契约不受影响。
 */
@RestController
@RequestMapping(value = "/open/chain", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "OpenChain")
public class OpenChainController {
    private final ChainService chainService;
    private final OpenAccessGuard accessGuard;

    public OpenChainController(ChainService chainService, OpenAccessGuard accessGuard) {
        this.chainService = chainService;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/dispatch")
    @Operation(summary = "下发任务链（chain_no 幂等；每段全量绑定授权校验）")
    public ApiResponse<Map<String,Object>> dispatchHttp(@RequestBody Map<String,Object> body,HttpServletRequest http) {
        Object raw=body.get("segments");
        if (raw instanceof List<?> list) for (Object item:list) {
            if (!(item instanceof Map<?,?> segment)) throw new com.cayleywcs.common.exception.WcsException(4241,"链段必须为对象");
            for (Object key:segment.keySet()) if (!Set.of("seq","appId","candidateAppIds","payload").contains(key))
                throw new com.cayleywcs.common.exception.WcsException(4241,"链段点位必须放在payload，不支持字段"+key);
        }
        var accepted=dispatch(RequestPayloads.parse(body,Set.of("chainNo","priority","selectStrategy","wmsRef","payload","segments"),
                ChainService.ChainDispatchRequest.class),http);
        return ApiResponse.success(OpenPublicViews.chain(accepted.data()));
    }

    public ApiResponse<TaskChainEntity> dispatch(ChainService.ChainDispatchRequest request,
                                                 HttpServletRequest http) {
        if (request.segments() != null) {
            for (ChainService.ChainSegment seg : request.segments()) {
                if (seg.appId() != null) {
                    accessGuard.assertCanCommand(http, seg.appId(), "chain/dispatch");
                }
                if (seg.candidateAppIds() != null) {
                    for (Long candidate : seg.candidateAppIds()) {
                        accessGuard.assertCanCommand(http, candidate, "chain/dispatch");
                    }
                }
            }
        }
        Long caller = accessGuard.resolveCaller(http);
        return ApiResponse.success(chainService.dispatchChain(request, caller == null ? 0L : caller));
    }

    @PostMapping("/query")
    @Operation(summary = "增量拉链（按 last_update_time 水位线；收敛为调用方自己下发的链）")
    public ApiResponse<ChainReconcileResult> query(@RequestBody ChainReconcileQuery q, HttpServletRequest http) {
        long now = System.currentTimeMillis();
        Long caller = accessGuard.resolveCaller(http); // null=appkey 关闭旁路：不限
        List<TaskChainEntity> rows = chainService.queryReconcile(caller, q.sinceMillis(), q.limit());
        return ApiResponse.success(new ChainReconcileResult(now, rows.size(), rows.stream().map(OpenPublicViews::chain).toList()));
    }

    public record ChainReconcileQuery(long sinceMillis, int limit) {
    }

    public record ChainReconcileResult(long serverTimeMillis, int count, List<Map<String,Object>> rows) {
    }
}
