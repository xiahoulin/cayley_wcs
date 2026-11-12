package com.cayleywcs.task.orchestrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.security.AppKeyAuthFilter;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.simulator.DeviceSimRegistry;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.support.PublishedDeviceFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M10 任务链编排端到端（H2 + sim 全自动）：跨设备链自动跑完、chain_no 幂等、崩溃重放不重复物化、
 * 级联取消、开放接口每段越权校验与下发者收敛。
 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class ChainOrchestrationTest {

    @Autowired
    private ChainService chainService;
    @Autowired
    private TaskOrchestrator orchestrator;
    @Autowired
    private ConnectionManager connectionManager;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private DeviceSimRegistry simRegistry;
    @Autowired
    private OpenChainController openChainController;
    @Autowired com.cayleywcs.protocol.ProtocolService fixtureProtocols;
    @Autowired com.cayleywcs.application.mapper.ApplicationMapper fixtureApps;
    @Autowired com.cayleywcs.binding.BindingService fixtureBindings;

    @BeforeEach void publishedFixtures() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new com.cayleywcs.auth.JwtUser(1L,"admin","admin","admin",1L,1L),null,List.of()));
        for(String code:List.of("STACKER01","CONVEYOR_SIM","LIFT_SIM")) {
            ApplicationEntity app=byCode(code);if(connectionManager.isOpen(app.getId()))connectionManager.close(app.getId());
            PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,app.getId(),switch(code){case "CONVEYOR_SIM"->"conveyor";case "LIFT_SIM"->"lift";default->"stacker";});
        }
        ApplicationEntity up=byCode("WMS-MAIN");PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,up.getId(),"none");
        fixtureBindings.grant(up.getId(),List.of(byCode("STACKER01").getId(),byCode("CONVEYOR_SIM").getId(),byCode("LIFT_SIM").getId()),"dispatch");
    }
    @AfterEach void clearIdentity() {org.springframework.security.core.context.SecurityContextHolder.clearContext();}

    private ApplicationEntity byCode(String appCode) {
        return applicationService.listAll().stream()
                .filter(a -> appCode.equals(a.getApp_code()))
                .findFirst().orElseThrow();
    }

    private void ensureOpen(Long appId) {
        if (!connectionManager.isOpen(appId)) {
            connectionManager.open(appId);
        }
    }

    @Test
    void chainRunsAcrossConveyorAndLiftToCompletion() throws Exception {
        ApplicationEntity conveyor = byCode("CONVEYOR_SIM");
        ApplicationEntity lift = byCode("LIFT_SIM");
        ensureOpen(conveyor.getId());
        ensureOpen(lift.getId());

        TaskChainEntity chain = chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-E2E-1", 0L, "idle_first", "WMS-REF-1", null, List.of(
                new ChainService.ChainSegment(1L, conveyor.getId(), null, "1",
                        null, null, null, null, null, null, null, Map.of("toStation", 7)),
                new ChainService.ChainSegment(2L, lift.getId(), null, "1",
                        null, null, null, null, null, null, null, Map.of("targetFloor", 5)))), 0L);

        // 引擎(100ms)/编排器(200ms)自动推进；手动帮 sim 加速节拍（心跳 1s 太慢）
        String status = chain.getStatus();
        for (int i = 0; i < 300; i++) {
            simRegistry.find(conveyor.getId()).ifPresent(com.cayleywcs.simulator.DeviceSim::tick);
            simRegistry.find(lift.getId()).ifPresent(com.cayleywcs.simulator.DeviceSim::tick);
            Thread.sleep(100);
            status = chainService.getById(chain.getId()).getStatus();
            if (ChainStatus.terminal(status)) {
                break;
            }
        }
        assertThat(status).isEqualTo(ChainStatus.COMPLETED);

        List<TaskEntity> tasks = chainService.tasksOf(chain.getId());
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).getTask_no()).isEqualTo("CHAIN-E2E-1-1");
        assertThat(tasks.get(0).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(tasks.get(1).getTask_no()).isEqualTo("CHAIN-E2E-1-2");
        assertThat(tasks.get(1).getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void dispatchIsIdempotentByChainNo() {
        ApplicationEntity stacker = byCode("STACKER01");
        ChainService.ChainDispatchRequest req = new ChainService.ChainDispatchRequest(
                "CHAIN-IDEM-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        1L, 2L, 3L, 1L, 4L, 5L, 1L, null)));
        TaskChainEntity first = chainService.dispatchChain(req, 0L);
        TaskChainEntity second = chainService.dispatchChain(req, 0L);
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void orchestratorReplayDoesNotDuplicateSegments() {
        // STACKER01 无连接：物化后子任务 pending 停驻，便于验证重放幂等
        ApplicationEntity stacker = byCode("STACKER01");
        TaskChainEntity chain = chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-REPLAY-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        1L, 2L, 3L, 1L, 4L, 5L, 1L, null))), 0L);

        orchestrator.runOnce();
        orchestrator.runOnce(); // 崩溃重放：确定性 task_no + 先查后插，不得重复物化

        List<TaskEntity> tasks = chainService.tasksOf(chain.getId());
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTask_no()).isEqualTo("CHAIN-REPLAY-1-1");
        assertThat(chainService.getById(chain.getId()).getStatus()).isEqualTo(ChainStatus.RUNNING);
    }

    @Test
    void cancelCascadesToPendingSegments() throws Exception {
        ApplicationEntity stacker = byCode("STACKER01");
        TaskChainEntity chain = chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-CANCEL-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        1L, 2L, 3L, 1L, 4L, 5L, 1L, null),
                new ChainService.ChainSegment(2L, stacker.getId(), null, "1",
                        1L, 2L, 3L, 1L, 4L, 5L, 1L, null))), 0L);

        // 等第 1 段物化（自动编排器也在跑）
        for (int i = 0; i < 50 && chainService.tasksOf(chain.getId()).isEmpty(); i++) {
            orchestrator.runOnce();
            Thread.sleep(50);
        }
        assertThat(chainService.tasksOf(chain.getId())).isNotEmpty();

        assertThat(chainService.cancel(chain.getId())).isTrue();
        assertThat(chainService.getById(chain.getId()).getStatus()).isEqualTo(ChainStatus.CANCELLED);
        assertThat(chainService.tasksOf(chain.getId()))
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(TaskStatus.CANCELLED));

        // 取消后编排器不得再物化第 2 段
        orchestrator.runOnce();
        assertThat(chainService.tasksOf(chain.getId())).hasSize(1);
    }

    @Test
    void openChainDispatchEnforcesPerSegmentAuthorization() {
        ApplicationEntity wms = byCode("WMS-MAIN");
        ApplicationEntity stacker = byCode("STACKER01");
        ApplicationEntity conveyor = byCode("CONVEYOR_SIM");

        MockHttpServletRequest asDevice = new MockHttpServletRequest();
        asDevice.setAttribute(AppKeyAuthFilter.ATTR_APP_ID, stacker.getId());
        asDevice.setAttribute(AppKeyAuthFilter.ATTR_APP_TENANT_ID, stacker.getTenant_id());
        // 设备身份对无绑定的候选段 → 整链 FORBIDDEN
        assertThatThrownBy(() -> openChainController.dispatch(new ChainService.ChainDispatchRequest(
                "CHAIN-AUTH-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, null, List.of(conveyor.getId()), "1",
                        null, null, null, null, null, null, null, Map.of("toStation", 1)))), asDevice))
                .isInstanceOf(WcsException.class)
                .hasMessageContaining("未授权");

        // WMS-MAIN（V10 已授权新设备）→ 放行
        MockHttpServletRequest asWms = new MockHttpServletRequest();
        asWms.setAttribute(AppKeyAuthFilter.ATTR_APP_ID, wms.getId());
        asWms.setAttribute(AppKeyAuthFilter.ATTR_APP_TENANT_ID, wms.getTenant_id());
        var resp = openChainController.dispatch(new ChainService.ChainDispatchRequest(
                "CHAIN-AUTH-2", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, null, List.of(conveyor.getId()), "1",
                        null, null, null, null, null, null, null, Map.of("toStation", 1)))), asWms);
        assertThat(resp.success()).isTrue();
        assertThat(resp.data().getUpstream_app_id()).isEqualTo(wms.getId());

        // query 收敛：设备身份看不到 WMS 下发的链
        var deviceView = openChainController.query(
                new OpenChainController.ChainReconcileQuery(0, 500), asDevice);
        assertThat(deviceView.data().rows().stream().map(row -> row.get("chain_no")))
                .doesNotContain("CHAIN-AUTH-2");
        var wmsView = openChainController.query(
                new OpenChainController.ChainReconcileQuery(0, 500), asWms);
        assertThat(wmsView.data().rows().stream().map(row -> row.get("chain_no")))
                .contains("CHAIN-AUTH-2");
    }

    @Test
    void uniqueIndexBlocksConcurrentDoubleMaterialization() {
        // 并发双物化的 DB 层兜底：同 (chain_id, seq_no) 第二条插入必须被唯一索引拒绝
        ApplicationEntity stacker = byCode("STACKER01");
        TaskChainEntity chain = chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-UNIQ-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        1L, 2L, 3L, 1L, 4L, 5L, 1L, null))), 0L);
        orchestrator.runOnce(); // 物化段 1

        TaskEntity dup = new TaskEntity();
        dup.setApp_id(stacker.getId());
        dup.setTask_no("CHAIN-UNIQ-1-1");
        dup.setChain_id(chain.getId());
        dup.setSeq_no(1L);
        dup.setTask_type("1");
        dup.setStatus(TaskStatus.PENDING);
        dup.setHandshake_step("CREATED");
        dup.setCreator("test");
        dup.setCreate_time(java.time.LocalDateTime.now());
        dup.setLast_update_time(java.time.LocalDateTime.now());
        dup.setIs_valid(true);
        dup.setTenant_id(1L);
        assertThatThrownBy(() -> taskMapperInsert(dup))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        // 独立任务（chain_id=NULL）不受唯一索引影响：NULL 彼此不同
        assertThat(chainService.tasksOf(chain.getId())).hasSize(1);
    }

    @Autowired
    private com.cayleywcs.task.mapper.TaskMapper taskMapper;

    private void taskMapperInsert(TaskEntity t) {
        taskMapper.insert(t);
    }

    @Test
    void singleTaskDispatchIdempotencyIgnoresChainSubTasks() {
        // 链子任务与单任务 task_no 撞名：单任务幂等不得误命中链子任务（命名空间隔离）
        ApplicationEntity stacker = byCode("STACKER01");
        TaskChainEntity chain = chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-NS-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        1L, 2L, 3L, 1L, 4L, 5L, 1L, null))), 0L);
        orchestrator.runOnce();
        List<TaskEntity> chainTasks = chainService.tasksOf(chain.getId());
        assertThat(chainTasks).hasSize(1); // task_no = CHAIN-NS-1-1

        // WMS 恰好用同名 taskNo 下发单任务 → 必须新建独立任务而非返回链子任务
        TaskEntity dispatched = taskServiceDispatch(stacker.getId(), "CHAIN-NS-1-1");
        assertThat(dispatched.getId()).isNotEqualTo(chainTasks.get(0).getId());
        assertThat(dispatched.getChain_id()).isNull();
        // 重发同名单任务 → 幂等命中独立任务
        assertThat(taskServiceDispatch(stacker.getId(), "CHAIN-NS-1-1").getId())
                .isEqualTo(dispatched.getId());
    }

    @Autowired
    private com.cayleywcs.task.TaskService taskService;

    private TaskEntity taskServiceDispatch(Long appId, String taskNo) {
        return taskService.dispatch(new com.cayleywcs.task.TaskService.DispatchRequest(
                appId, taskNo, "1", 1L, 2L, 3L, 1L, 4L, 5L, 1L, 0L, "", null));
    }

    @Test
    void chainNoAndStrategyValidation() {
        ApplicationEntity stacker = byCode("STACKER01");
        // chainNo 超长拒绝（子任务号需容于 128）
        assertThatThrownBy(() -> chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "X".repeat(101), 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        null, null, null, null, null, null, null, null))), 0L))
                .isInstanceOf(WcsException.class)
                .hasMessageContaining("过长");
        // 未知选机策略拒绝（不再静默回落）
        assertThatThrownBy(() -> chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-STRAT-BAD", 0L, "fastest_typo", "", null, List.of(
                new ChainService.ChainSegment(1L, null, List.of(stacker.getId()), "1",
                        null, null, null, null, null, null, null, null))), 0L))
                .isInstanceOf(WcsException.class)
                .hasMessageContaining("选机策略");
    }

    @Test
    void segmentValidationRejectsBadInput() {
        ApplicationEntity stacker = byCode("STACKER01");
        // appId 与候选都缺
        assertThatThrownBy(() -> chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-BAD-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, null, null, "1",
                        null, null, null, null, null, null, null, null))), 0L))
                .isInstanceOf(WcsException.class);
        // 重复段号
        assertThatThrownBy(() -> chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-BAD-2", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        null, null, null, null, null, null, null, null),
                new ChainService.ChainSegment(1L, stacker.getId(), null, "1",
                        null, null, null, null, null, null, null, null))), 0L))
                .isInstanceOf(WcsException.class);
        // 空段
        assertThatThrownBy(() -> chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "CHAIN-BAD-3", 0L, null, "", null, List.of()), 0L))
                .isInstanceOf(WcsException.class);
    }
}
