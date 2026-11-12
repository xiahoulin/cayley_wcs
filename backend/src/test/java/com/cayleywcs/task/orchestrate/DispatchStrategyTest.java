package com.cayleywcs.task.orchestrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.support.PublishedDeviceFixtures;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M10 派工/选机策略与队列管理：fifo vs priority 排序、work_mode 暂停派工（在飞放行）、
 * idle_first 避开暂停/未连接设备、round_robin 决定性、reprioritize/retry。
 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class DispatchStrategyTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private ApplicationMapper applicationMapper;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private ConnectionManager connectionManager;
    @Autowired
    private IdleFirstStrategy idleFirst;
    @Autowired
    private RoundRobinStrategy roundRobin;
    @Autowired ProtocolService protocols;

    /** 直插一个测试设备应用（绕过管理端登录要求）。 */
    private ApplicationEntity newApp(String code, String dispatchStrategy) {
        ApplicationEntity app = new ApplicationEntity();
        app.setApp_code(code);
        app.setApp_name(code);
        app.setApp_key("ak_" + code.toLowerCase());
        app.setApp_secret("s");
        app.setDirection("downstream");
        app.setDevice_type("stacker");
        app.setWork_mode("normal");
        app.setDispatch_strategy(dispatchStrategy);
        app.setEnabled(true);
        app.setStatus("idle");
        app.setCreator("test");
        app.setCreate_time(LocalDateTime.now());
        app.setLast_update_time(LocalDateTime.now());
        app.setIs_valid(true);
        app.setTenant_id(1L);
        applicationMapper.insert(app);
        return PublishedDeviceFixtures.ensure(protocols,applicationMapper,app.getId(),"stacker");
    }

    private TaskEntity newTask(Long appId, String no, long priority) {
        TaskEntity t = new TaskEntity();
        t.setApp_id(appId);
        t.setTask_no(no);
        t.setTask_type("1");
        t.setPriority(priority);
        t.setPayload(PublishedDeviceFixtures.stackerPayload());
        return taskService.create(t);
    }

    @Test
    void fifoIgnoresPriorityWhilePriorityStrategyHonorsIt() {
        ApplicationEntity fifoApp = newApp("STRAT-FIFO", "fifo");
        newTask(fifoApp.getId(), "F-1", 0);
        newTask(fifoApp.getId(), "F-2", 99); // 高优先级但后下发

        assertThat(taskService.findNextForApp(fifoApp.getId()).getTask_no())
                .as("fifo 严格按下发顺序").isEqualTo("F-1");

        applicationMapper.update(null, new UpdateWrapper<ApplicationEntity>()
                .eq("\"id\"", fifoApp.getId()).set("\"dispatch_strategy\"", "priority"));
        assertThat(taskService.findNextForApp(fifoApp.getId()).getTask_no())
                .as("priority 取最高优先级").isEqualTo("F-2");
    }

    @Test
    void pausedAppStopsNewDispatchButInFlightContinues() {
        ApplicationEntity app = newApp("STRAT-PAUSE", "priority");
        newTask(app.getId(), "P-1", 0);

        applicationService.updateWorkMode(app.getId(), "paused");
        assertThat(taskService.findNextForApp(app.getId())).as("暂停停新派工").isNull();

        // 在飞任务不受暂停影响（graceful：跑完为止）
        TaskEntity inFlight = newTask(app.getId(), "P-2", 0);
        inFlight.setStatus(TaskStatus.EXECUTING);
        taskMapper.updateById(inFlight);
        assertThat(taskService.findNextForApp(app.getId()).getTask_no()).isEqualTo("P-2");

        applicationService.updateWorkMode(app.getId(), "normal");
        assertThat(taskService.findNextForApp(app.getId())).isNotNull();
    }

    @Test
    void updateWorkModeRejectsIllegalValue() {
        ApplicationEntity app = newApp("STRAT-MODE", "priority");
        assertThatThrownBy(() -> applicationService.updateWorkMode(app.getId(), "banana"))
                .isInstanceOf(WcsException.class);
    }

    @Test
    void idleFirstSkipsPausedAndUnconnectedPrefersIdle() {
        // LIFT_SIM 开连接且空闲；一个新设备无连接；一个暂停设备
        ApplicationEntity lift = applicationService.listAll().stream()
                .filter(a -> "LIFT_SIM".equals(a.getApp_code())).findFirst().orElseThrow();
        lift=PublishedDeviceFixtures.ensure(protocols,applicationMapper,lift.getId(),"lift");
        if (!connectionManager.isOpen(lift.getId())) {
            connectionManager.open(lift.getId());
        }
        ApplicationEntity offline = newApp("STRAT-OFFLINE", "priority");
        ApplicationEntity paused = newApp("STRAT-PAUSED2", "priority");
        applicationService.updateWorkMode(paused.getId(), "paused");

        Long picked = idleFirst.select(List.of(offline.getId(), paused.getId(), lift.getId()),
                new DeviceSelectionStrategy.SelectionContext(1L, 1L));
        assertThat(picked).as("唯一可用且空闲的是 LIFT_SIM").isEqualTo(lift.getId());

        Long none = idleFirst.select(List.of(offline.getId(), paused.getId()),
                new DeviceSelectionStrategy.SelectionContext(1L, 1L));
        assertThat(none).as("全不可用返回 null 等待").isNull();
    }

    @Test
    void roundRobinIsDeterministicModulo() {
        List<Long> candidates = List.of(11L, 22L, 33L);
        var ctx1 = new DeviceSelectionStrategy.SelectionContext(10L, 1L); // (10+1)%3=2 → 33
        var ctx2 = new DeviceSelectionStrategy.SelectionContext(10L, 2L); // (10+2)%3=0 → 11
        assertThat(roundRobin.select(candidates, ctx1)).isEqualTo(33L);
        assertThat(roundRobin.select(candidates, ctx2)).isEqualTo(11L);
        assertThat(roundRobin.select(candidates, ctx1)).isEqualTo(33L); // 重复调用决定性
    }

    @Test
    void reprioritizeAndRetryLifecycle() {
        ApplicationEntity app = newApp("STRAT-RETRY", "priority");
        TaskEntity t = newTask(app.getId(), "R-1", 0);

        assertThat(taskService.reprioritize(t.getId(), 50L)).isTrue();
        assertThat(taskService.getById(t.getId()).getPriority()).isEqualTo(50L);

        // 置失败 → 重试回 pending，retry_count++
        TaskEntity failed = taskService.getById(t.getId());
        failed.setStatus(TaskStatus.FAILED);
        taskMapper.updateById(failed);
        applicationService.updateWorkMode(app.getId(),"paused");
        if(!connectionManager.isOpen(app.getId()))connectionManager.open(app.getId());
        assertThat(taskService.retryTask(t.getId())).isTrue();
        TaskEntity retried = taskService.getById(t.getId());
        assertThat(retried.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(retried.getRetry_count()).isEqualTo(1L);

        // 非失败态重试拒绝
        assertThatThrownBy(() -> taskService.retryTask(t.getId()))
                .isInstanceOf(WcsException.class);

        // 链子任务重试拒绝（走链级处理）
        TaskEntity chained = newTask(app.getId(), "R-2", 0);
        chained.setStatus(TaskStatus.FAILED);
        chained.setChain_id(12345L);
        taskMapper.updateById(chained);
        assertThatThrownBy(() -> taskService.retryTask(chained.getId()))
                .isInstanceOf(WcsException.class)
                .hasMessageContaining("链");
    }
}
