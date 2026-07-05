package com.cayleywcs.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.DeviceHandler;
import com.cayleywcs.device.DeviceHandlerRegistry;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 任务引擎并发模型（M8/F1/F2）：设备级单飞互斥（慢 PLC 下同 app 只有一个 advance 进入）、
 * 乐观锁 save 冲突丢弃本步不抛异常、未知设备类型报警不静默。
 */
class TaskEngineConcurrencyTest {
    private static final long APP_ID = 1L;

    /** 可控慢 handler：advance 阻塞在 latch 上模拟慢 PLC I/O。 */
    private static final class SlowHandler implements DeviceHandler {
        final AtomicInteger entries = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        @Override
        public String deviceType() {
            return "slowdev";
        }

        @Override
        public boolean advance(TaskEntity task) {
            entries.incrementAndGet();
            entered.countDown();
            try {
                proceed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    @Test
    void singleFlightPerAppUnderSlowPlc() throws Exception {
        SlowHandler handler = new SlowHandler();
        TaskService taskService = mock(TaskService.class);
        when(taskService.findNextForApp(APP_ID)).thenAnswer(inv -> new TaskEntity());
        when(taskService.save(any())).thenReturn(true);
        TaskEngine engine = new TaskEngine(mock(ConnectionManager.class), taskService,
                new DeviceHandlerRegistry(List.of(handler)), mock(AlarmService.class), 4);

        Thread first = new Thread(() -> engine.advanceApp(APP_ID, "slowdev"));
        first.start();
        assertThat(handler.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 第一步还卡在"PLC"上时，同 app 再次推进必须被单飞挡回，不进 handler
        engine.advanceApp(APP_ID, "slowdev");
        assertThat(handler.entries.get()).isEqualTo(1);

        handler.proceed.countDown();
        first.join(5000);
        assertThat(first.isAlive()).isFalse();

        // 释放后可再次推进
        engine.advanceApp(APP_ID, "slowdev");
        assertThat(handler.entries.get()).isEqualTo(2);
        engine.shutdown();
    }

    @Test
    void optimisticLockConflictDropsStepQuietly() {
        DeviceHandler handler = new DeviceHandler() {
            @Override
            public String deviceType() {
                return "dev";
            }

            @Override
            public boolean advance(TaskEntity task) {
                return true;
            }
        };
        TaskService taskService = mock(TaskService.class);
        when(taskService.findNextForApp(APP_ID)).thenReturn(new TaskEntity());
        when(taskService.save(any())).thenReturn(false); // 并发写赢了：版本冲突
        TaskEngine engine = new TaskEngine(mock(ConnectionManager.class), taskService,
                new DeviceHandlerRegistry(List.of(handler)), mock(AlarmService.class), 4);

        engine.advanceApp(APP_ID, "dev"); // 不得抛异常
        verify(taskService, times(1)).save(any());
        engine.shutdown();
    }

    @Test
    void unknownDeviceTypeRaisesAlarmNotSilent() {
        TaskService taskService = mock(TaskService.class);
        TaskEntity task = new TaskEntity();
        task.setTask_no("T1");
        when(taskService.findNextForApp(APP_ID)).thenReturn(task);
        AlarmService alarmService = mock(AlarmService.class);
        TaskEngine engine = new TaskEngine(mock(ConnectionManager.class), taskService,
                new DeviceHandlerRegistry(List.of()), alarmService, 4);

        engine.advanceApp(APP_ID, "ghost");

        verify(alarmService).raise(eq(APP_ID), eq(TaskEngine.FAULT_CODE_UNKNOWN_DEVICE_TYPE),
                eq("error"), anyString(), anyString());
        verify(taskService, times(0)).save(any()); // 任务保持 pending 不动
        engine.shutdown();
    }

    @Test
    void noTaskMeansNoHandlerLookupNorAlarm() {
        // 上位系统(device_type=none)或空闲设备：无任务直接返回，未知类型也不误报警
        TaskService taskService = mock(TaskService.class);
        when(taskService.findNextForApp(anyLong())).thenReturn(null);
        AlarmService alarmService = mock(AlarmService.class);
        TaskEngine engine = new TaskEngine(mock(ConnectionManager.class), taskService,
                new DeviceHandlerRegistry(List.of()), alarmService, 4);

        engine.advanceApp(APP_ID, "none");

        verify(alarmService, times(0)).raise(anyLong(), anyLong(), anyString(), anyString(), anyString());
        engine.shutdown();
    }
}
