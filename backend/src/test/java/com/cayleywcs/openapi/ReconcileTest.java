package com.cayleywcs.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.alarm.entity.AlarmEntity;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 对账增量查询（水位线）：sinceMillis=0 全量、未来时间为空、新增/更新可被拉到。 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class ReconcileTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private AlarmService alarmService;

    @Test
    void taskReconcileWatermark() {
        long appId = 70001L;
        taskService.create(task(appId, "RC-1"));
        taskService.create(task(appId, "RC-2"));

        List<TaskEntity> all = taskService.queryReconcile(appId, 0, 100);
        assertThat(all).hasSize(2);
        // 升序：按 last_update_time
        assertThat(all.get(0).getLast_update_time()).isBeforeOrEqualTo(all.get(1).getLast_update_time());

        List<TaskEntity> future = taskService.queryReconcile(appId, System.currentTimeMillis() + 60_000, 100);
        assertThat(future).isEmpty();
    }

    @Test
    void alarmReconcileWatermark() {
        long appId = 70002L;
        alarmService.raise(appId, 5L, "warn", "测试报警", "处置");

        List<AlarmEntity> all = alarmService.queryReconcile(appId, 0, 100);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getFault_code()).isEqualTo(5L);

        List<AlarmEntity> future = alarmService.queryReconcile(appId, System.currentTimeMillis() + 60_000, 100);
        assertThat(future).isEmpty();
    }

    private TaskEntity task(long appId, String no) {
        TaskEntity t = new TaskEntity();
        t.setApp_id(appId);
        t.setTask_no(no);
        t.setTask_type("1");
        return t;
    }
}
