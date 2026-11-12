package com.cayleywcs.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.audit.MessageLogCleaner;
import com.cayleywcs.audit.entity.ConnectionLogEntity;
import com.cayleywcs.audit.entity.MessageLogEntity;
import com.cayleywcs.audit.mapper.ConnectionLogMapper;
import com.cayleywcs.audit.mapper.MessageLogMapper;
import com.cayleywcs.task.TaskRecoveryScheduler;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskChainEntity;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import com.cayleywcs.task.orchestrate.ChainService;
import com.cayleywcs.task.orchestrate.ChainStatus;
import com.cayleywcs.task.orchestrate.TaskOrchestrator;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M11 设备维护与统计：auto_retry 闭环（独立任务+链段）/耗尽转人工/退避/在飞停滞判失败+报警/
 * 统计三接口 fixture 断言/报文留存清理。
 */
@SpringBootTest(properties={"cayleywcs.task.engine-interval-ms=1000000000","cayleywcs.chain.orchestrate-interval-ms=1000000000","cayleywcs.task.recovery-interval-ms=1000000000"})
@ActiveProfiles("test-flyway")
class RecoveryAndStatsTest {
    @Autowired private com.cayleywcs.protocol.ProtocolService fixtureProtocols;
    @Autowired private com.cayleywcs.application.mapper.ApplicationMapper fixtureApps;
    @Autowired private com.cayleywcs.connection.ConnectionManager fixtureConnections;
    @Autowired private com.cayleywcs.application.ApplicationService fixtureApplicationService;


    @Autowired
    private TaskRecoveryScheduler recoveryScheduler;
    @Autowired
    private TaskOrchestrator orchestrator;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ChainService chainService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private ApplicationMapper applicationMapper;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private ConnectionLogMapper connectionLogMapper;
    @Autowired
    private MessageLogMapper messageLogMapper;
    @Autowired
    private StatsService statsService;
    @Autowired
    private MessageLogCleaner messageLogCleaner;

    private ApplicationEntity newApp(String code, String failurePolicy, long maxRetry, long staleSeconds) {
        ApplicationEntity app = new ApplicationEntity();
        app.setApp_code(code);
        app.setApp_name(code);
        app.setApp_key("ak_" + code.toLowerCase());
        app.setApp_secret("s");
        app.setDirection("downstream");
        app.setDevice_type("stacker");
        app.setWork_mode("normal");
        app.setFailure_policy(failurePolicy);
        app.setMax_retry(maxRetry);
        app.setTask_stale_seconds(staleSeconds);
        app.setEnabled(true);
        app.setStatus("idle");
        app.setCreator("test");
        app.setCreate_time(LocalDateTime.now());
        app.setLast_update_time(LocalDateTime.now());
        app.setIs_valid(true);
        app.setTenant_id(1L);
        applicationMapper.insert(app);
        return com.cayleywcs.support.PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,app.getId(),"stacker");
    }

    private TaskEntity newTask(Long appId, String no, String status) {
        TaskEntity t = new TaskEntity();
        t.setApp_id(appId);
        t.setTask_no(no);
        t.setTask_type("1");
        t.setPayload(com.cayleywcs.support.PublishedDeviceFixtures.stackerPayload());
        t = taskService.create(t);
        if (!TaskStatus.PENDING.equals(status)) {
            taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                    .eq("\"id\"", t.getId()).set("\"status\"", status));
        }
        return taskService.getById(t.getId());
    }

    @Test
    void autoRetryReschedulesFailedStandaloneTask() {
        ApplicationEntity app = newApp("REC-AUTO", "auto_retry", 2, 0);
        TaskEntity t = newTask(app.getId(), "REC-1", TaskStatus.FAILED);
        ageTask(t.getId()); // 退避到点（测试配置大退避防自动抢跑）
        fixtureApplicationService.updateWorkMode(app.getId(),"paused");
        fixtureConnections.open(app.getId());

        recoveryScheduler.runOnce();

        TaskEntity after = taskService.getById(t.getId());
        assertThat(after.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(after.getRetry_count()).isEqualTo(1L);
        assertThat(after.getHandshake_step()).isEqualTo("CREATED");
    }

    @Test
    void manualPolicyKeepsFailedTerminal() {
        ApplicationEntity app = newApp("REC-MANUAL", "manual", 5, 0);
        TaskEntity t = newTask(app.getId(), "REC-2", TaskStatus.FAILED);

        recoveryScheduler.runOnce();

        assertThat(taskService.getById(t.getId()).getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void exhaustedRetryStaysFailed() {
        ApplicationEntity app = newApp("REC-EXH", "auto_retry", 1, 0);
        TaskEntity t = newTask(app.getId(), "REC-3", TaskStatus.FAILED);
        taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                .eq("\"id\"", t.getId())
                .set("\"retry_count\"", 1) // 已重试 1 次 = 上限
                .set("\"last_update_time\"", LocalDateTime.now().minusMinutes(30))); // 退避已到点仍不得重试

        recoveryScheduler.runOnce();

        assertThat(taskService.getById(t.getId()).getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void backoffNotDueSkipsRetry() {
        ApplicationEntity app = newApp("REC-BACKOFF", "auto_retry", 3, 0);
        TaskEntity t = newTask(app.getId(), "REC-4", TaskStatus.FAILED); // last_update_time=刚刚，10min 退避未到点

        recoveryScheduler.runOnce();

        assertThat(taskService.getById(t.getId()).getStatus())
                .as("退避未到点不得重派").isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void chainSegmentAutoRetriesThenFailsChainWhenExhausted() throws Exception {
        ApplicationEntity app = newApp("REC-CHAIN", "auto_retry", 2, 0);
        TaskChainEntity chain = chainService.dispatchChain(new ChainService.ChainDispatchRequest(
                "REC-CHAIN-1", 0L, null, "", null, List.of(
                new ChainService.ChainSegment(1L, app.getId(), null, "1",
                        1L, 2L, 3L, 1L, 4L, 5L, 1L, null))), 0L);
        // 等物化（自动编排器也在跑）
        for (int i = 0; i < 50 && chainService.tasksOf(chain.getId()).isEmpty(); i++) {
            orchestrator.runOnce();
            Thread.sleep(20);
        }
        TaskEntity seg = chainService.tasksOf(chain.getId()).get(0);
        fixtureApplicationService.updateWorkMode(app.getId(),"paused");
        fixtureConnections.open(app.getId());

        // 段失败（未耗尽、退避到点）→ 编排器自动重派，链保持非终态
        taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                .eq("\"id\"", seg.getId())
                .set("\"status\"", TaskStatus.FAILED)
                .set("\"last_update_time\"", LocalDateTime.now().minusMinutes(30)));
        orchestrator.runOnce();
        TaskEntity after = taskService.getById(seg.getId());
        assertThat(after.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(after.getRetry_count()).isEqualTo(1L);
        assertThat(ChainStatus.terminal(chainService.getById(chain.getId()).getStatus())).isFalse();

        // 段失败且耗尽 → 链 failed（转人工）
        taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                .eq("\"id\"", seg.getId())
                .set("\"status\"", TaskStatus.FAILED)
                .set("\"retry_count\"", 2)
                .set("\"last_update_time\"", LocalDateTime.now().minusMinutes(30)));
        orchestrator.runOnce();
        assertThat(chainService.getById(chain.getId()).getStatus()).isEqualTo(ChainStatus.FAILED);
    }

    @Test
    void staleInFlightTaskFailsWithAlarm() {
        ApplicationEntity app = newApp("REC-STALE", "manual", 0, 1); // 停滞阈值 1s
        TaskEntity t = newTask(app.getId(), "REC-5", TaskStatus.PENDING);
        taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                .eq("\"id\"", t.getId())
                .set("\"status\"", TaskStatus.EXECUTING)
                .set("\"last_update_time\"", LocalDateTime.now().minusSeconds(60)));

        recoveryScheduler.runOnce();

        assertThat(taskService.getById(t.getId()).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(alarmService.listActive(app.getId()))
                .anySatisfy(a -> assertThat(a.getFault_code())
                        .isEqualTo(TaskRecoveryScheduler.FAULT_CODE_TASK_STALE));
    }

    @Test
    void taskSummaryAggregatesFixture() {
        ApplicationEntity app = newApp("STATS-SUM", "manual", 0, 0);
        // 2 完成（执行时长 2s/4s，用同一基准时刻保证毫秒级精确）+ 1 失败(码 7) + 1 pending
        LocalDateTime base = LocalDateTime.now();
        for (int i = 1; i <= 2; i++) {
            TaskEntity done = newTask(app.getId(), "SUM-C" + i, TaskStatus.PENDING);
            taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                    .eq("\"id\"", done.getId())
                    .set("\"status\"", TaskStatus.COMPLETED)
                    .set("\"dispatch_time\"", base.minusSeconds(10))
                    .set("\"finish_time\"", base.minusSeconds(10 - 2L * i)));
        }
        TaskEntity failed = newTask(app.getId(), "SUM-F1", TaskStatus.PENDING);
        taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                .eq("\"id\"", failed.getId())
                .set("\"status\"", TaskStatus.FAILED)
                .set("\"error_code\"", 7));
        newTask(app.getId(), "SUM-P1", TaskStatus.PENDING);

        StatsService.TaskSummary s = statsService.taskSummary(app.getId(), 0, 0);
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.completed()).isEqualTo(2);
        assertThat(s.failed()).isEqualTo(1);
        assertThat(s.pending()).isEqualTo(1);
        assertThat(s.completionRate()).isEqualTo(2.0 / 3.0);
        assertThat(s.avgExecMillis()).isEqualTo(3000L); // (2s+4s)/2
        assertThat(s.failTop()).hasSize(1);
        assertThat(s.failTop().get(0).errorCode()).isEqualTo(7L);
    }

    @Test
    void deviceUtilizationReplaysConnectionLog() {
        long appId = 88801L;
        LocalDateTime t0 = LocalDateTime.now().minusHours(2);
        // 窗口 [t0, t0+10min]：t0+1min RUNNING → t0+6min DISCONNECTED = 5min RUNNING → 0.5
        insertConnLog(appId, "RUNNING", t0.plusMinutes(1));
        insertConnLog(appId, "DISCONNECTED", t0.plusMinutes(6));

        List<StatsService.DeviceUtilization> rows = statsService.deviceUtilization(appId,
                toMillis(t0), toMillis(t0.plusMinutes(10)));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).runningMillis()).isEqualTo(5 * 60 * 1000L);
        assertThat(rows.get(0).utilization()).isEqualTo(0.5);
    }

    @Test
    void alarmTopCountsByFaultCode() {
        long appId = 88802L;
        com.cayleywcs.support.PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,appId,"stacker");
        alarmService.raise(appId, 5L, "warn", "m1", "s");
        alarmService.clearFault(appId, 5L);
        alarmService.raise(appId, 5L, "warn", "m2", "s"); // 清除后再发 = 第二行
        alarmService.raise(appId, 9L, "error", "m3", "s");

        List<StatsService.AlarmTop> top = statsService.alarmTop(appId, 0, 0, 10);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).faultCode()).isEqualTo(5L);
        assertThat(top.get(0).count()).isEqualTo(2L);
        assertThat(top.get(1).faultCode()).isEqualTo(9L);
    }

    @Test
    void connectionRecoveryClearsOnlyCommAlarm() {
        // -4 停滞报警不得随连接恢复被清（任务侧问题未解决）
        long appId = 88803L;
        com.cayleywcs.support.PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,appId,"stacker");
        alarmService.raise(appId, -1L, "error", "连接断开", "s");
        alarmService.raise(appId, TaskRecoveryScheduler.FAULT_CODE_TASK_STALE, "error", "任务停滞", "s");

        alarmService.clearCommAlarms(appId);

        var active = alarmService.listActive(appId);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getFault_code()).isEqualTo(TaskRecoveryScheduler.FAULT_CODE_TASK_STALE);
    }

    @Test
    void cleanerRemovesExpiredKeepsFresh() {
        MessageLogEntity old = msgLog(77701L, LocalDateTime.now().minusDays(30));
        MessageLogEntity fresh = msgLog(77701L, LocalDateTime.now());
        messageLogMapper.insert(old);
        messageLogMapper.insert(fresh);

        int deleted = messageLogCleaner.runOnce();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(messageLogMapper.selectById(old.getId())).isNull();
        assertThat(messageLogMapper.selectById(fresh.getId())).isNotNull();
    }

    private void insertConnLog(long appId, String state, LocalDateTime at) {
        ConnectionLogEntity row = new ConnectionLogEntity();
        row.setApp_id(appId);
        row.setApp_code("UTIL-APP");
        row.setEvent("STATE_CHANGE");
        row.setState(state);
        row.setDetail("test");
        row.setCreate_time(at);
        row.setTenant_id(1L);
        connectionLogMapper.insert(row);
    }

    /** 把任务 last_update_time 改老，使退避到点（测试配置为 10min 大退避，防自动调度器抢跑）。 */
    private void ageTask(Long taskId) {
        taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                .eq("\"id\"", taskId)
                .set("\"last_update_time\"", LocalDateTime.now().minusMinutes(30)));
    }

    private MessageLogEntity msgLog(long appId, LocalDateTime at) {
        MessageLogEntity row = new MessageLogEntity();
        row.setApp_id(appId);
        row.setDirection("read");
        row.setField_name("f");
        row.setAddress("a");
        row.setRaw_value("1");
        row.setJson_payload("{}");
        row.setCreate_time(at);
        row.setTenant_id(1L);
        return row;
    }

    private static long toMillis(LocalDateTime t) {
        return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
