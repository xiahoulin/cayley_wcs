package com.cayleywcs.connection;

import com.cayleywcs.task.entity.TaskEntity;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 任务、运维和配置切换共享设备锁；线程上下文仅用于内部驱动阶段校验。 */
@Component
public class DeviceExecutionCoordinator {
    private final ConcurrentHashMap<Long,ReentrantLock> locks=new ConcurrentHashMap<>();
    private final ThreadLocal<TaskEntity> task=new ThreadLocal<>();
    public <T> T exclusive(Long appId,Supplier<T> body) {
        ReentrantLock lock=locks.computeIfAbsent(appId,id->new ReentrantLock(true));
        lock.lock();
        try { return body.get(); } finally { lock.unlock(); }
    }
    public void runTask(TaskEntity current,Runnable body) {
        exclusive(current.getApp_id(),()->{
            task.set(current); try {body.run();} finally {task.remove();} return null;
        });
    }
    public TaskEntity currentTask() { return task.get(); }
}
