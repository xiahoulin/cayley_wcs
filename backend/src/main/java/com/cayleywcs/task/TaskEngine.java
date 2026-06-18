package com.cayleywcs.task;

import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.ConnectionSnapshot;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务调度引擎：周期为每个 RUNNING 连接推进其下一个任务（设备级单飞），驱动握手状态机。
 */
@Component
public class TaskEngine {
    private static final Logger log = LoggerFactory.getLogger(TaskEngine.class);

    private final ConnectionManager connectionManager;
    private final TaskService taskService;
    private final StackerHandshakeStateMachine stateMachine;
    private final Set<Long> busy = ConcurrentHashMap.newKeySet();

    public TaskEngine(ConnectionManager connectionManager, TaskService taskService,
                      StackerHandshakeStateMachine stateMachine) {
        this.connectionManager = connectionManager;
        this.taskService = taskService;
        this.stateMachine = stateMachine;
    }

    @Scheduled(fixedDelayString = "${cayleywcs.task.engine-interval-ms:1000}")
    public void tick() {
        for (ConnectionSnapshot snap : connectionManager.snapshots()) {
            if (!"RUNNING".equals(snap.state())) {
                continue;
            }
            advanceApp(snap.appId());
        }
    }

    /** 推进某应用的下一个任务一步。设备级单飞：同一应用同时只处理一个任务一步。 */
    public void advanceApp(Long appId) {
        if (!busy.add(appId)) {
            return;
        }
        try {
            TaskEntity task = taskService.findNextForApp(appId);
            if (task == null) {
                return;
            }
            stateMachine.advance(task);
            taskService.save(task);
        } catch (RuntimeException ex) {
            log.warn("task engine error for app {}: {}", appId, ex.getMessage());
        } finally {
            busy.remove(appId);
        }
    }
}
