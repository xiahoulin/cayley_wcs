package com.cayleywcs.task.orchestrate;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 任务链管理端（JWT）：列表 / 详情（链+子任务）/ 级联取消。 */
@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chain")
public class ChainController {
    private final ChainService chainService;

    public ChainController(ChainService chainService) {
        this.chainService = chainService;
    }

    @PostMapping("/chain/list")
    @Operation(summary = "任务链分页列表")
    public ApiResponse<PageData<TaskChainEntity>> list(@RequestBody PageSearch pageSearch) {
        return ApiResponse.success(chainService.page(pageSearch));
    }

    @PostMapping("/chain/detail")
    @Operation(summary = "链详情：链 + 已物化子任务")
    public ApiResponse<ChainDetail> detail(@RequestBody IdRequest request) {
        TaskChainEntity chain = chainService.getById(request.id());
        return ApiResponse.success(new ChainDetail(chain, chainService.tasksOf(chain.getId())));
    }

    @PostMapping("/chain/cancel")
    @Operation(summary = "级联取消（pending 子任务同取消；在飞段跑完不再物化下段）")
    public ApiResponse<Boolean> cancel(@RequestBody IdRequest request) {
        return ApiResponse.success(chainService.cancel(request.id()));
    }

    public record ChainDetail(TaskChainEntity chain, List<TaskEntity> tasks) {
    }
}
