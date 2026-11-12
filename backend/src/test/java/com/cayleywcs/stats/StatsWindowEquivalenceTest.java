package com.cayleywcs.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.cayleywcs.common.support.ReconcileSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 固定窗口与并列 TOP：锁定旧版状态分类、逐行毫秒截断及最新消息口径。 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class StatsWindowEquivalenceTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StatsService stats;

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 25, 10, 0);
    private static final LocalDateTime TO = FROM.plusHours(1);

    @Test
    void taskSummaryKeepsClassificationTimeWindowAndMillisecondTruncation() {
        long appId = 99151L;
        insertTask(appId, "T-C1", "completed", FROM, FROM, FROM.plusNanos(1_123_456_000), 0);
        insertTask(appId, "T-C2", "completed", FROM.plusMinutes(1), null, null, 0);
        insertTask(appId, "T-F1", "failed", FROM.plusMinutes(2), null, null, 7);
        insertTask(appId, "T-F2", "failed", FROM.plusMinutes(3), null, null, 7);
        insertTask(appId, "T-X", "cancelled", FROM.plusMinutes(4), null, null, 0);
        insertTask(appId, "T-U", "unexpected", FROM.plusMinutes(5), null, null, 0);
        insertTask(appId, "T-OUT", "failed", TO.plusSeconds(1), null, null, 8);

        StatsService.TaskSummary actual = stats.taskSummary(appId, millis(FROM), millis(TO));
        assertThat(actual.total()).isEqualTo(6);
        assertThat(actual.completed()).isEqualTo(2);
        assertThat(actual.failed()).isEqualTo(2);
        assertThat(actual.cancelled()).isEqualTo(1);
        assertThat(actual.inFlight()).isZero();
        assertThat(actual.pending()).isEqualTo(1);
        assertThat(actual.completionRate()).isEqualTo(0.5);
        assertThat(actual.avgExecMillis()).isEqualTo(1123);
        assertThat(actual.failTop()).containsExactly(new StatsService.FailCode(7, 2));
    }

    @Test
    void alarmTopKeepsLatestRowAndEarliestRowTieOrder() {
        long appId = 99152L;
        insertAlarm(appId, 10, "warn", "old-10", FROM);
        insertAlarm(appId, 20, "warn", "old-20", FROM.plusSeconds(1));
        insertAlarm(appId, 20, "error", "new-20", FROM.plusSeconds(2));
        insertAlarm(appId, 10, "error", "new-10", FROM.plusSeconds(3));
        insertAlarm(appId, 30, "error", "outside", TO.plusSeconds(1));

        List<StatsService.AlarmTop> actual = stats.alarmTop(appId, millis(FROM), millis(TO), 2);
        assertThat(actual).containsExactly(
                new StatsService.AlarmTop(10, 2, "error", "new-10"),
                new StatsService.AlarmTop(20, 2, "error", "new-20"));
        assertThat(stats.alarmTop(appId, millis(FROM), millis(TO), 1))
                .containsExactly(new StatsService.AlarmTop(10, 2, "error", "new-10"));
    }

    @Test
    void utilizationReplaysPreWindowStateAndUsesLastAppCode() {
        long appId = 99153L;
        insertLog(appId, "OLD", "RUNNING", FROM.minusMinutes(10));
        insertLog(appId, "NEW", "DISCONNECTED", FROM.plusMinutes(40));
        insertLog(appId, "NEW", "RUNNING", FROM.plusMinutes(50));
        StatsService.DeviceUtilization actual = stats.deviceUtilization(appId, millis(FROM), millis(TO)).getFirst();
        assertThat(actual.appCode()).isEqualTo("NEW");
        assertThat(actual.runningMillis()).isEqualTo(3_000_000);
        assertThat(actual.windowMillis()).isEqualTo(3_600_000);
        assertThat(actual.utilization()).isEqualTo(5.0 / 6.0);
        assertThat(stats.deviceUtilization(99154L, millis(FROM), millis(TO))).isEmpty();
    }

    private void insertTask(long appId, String no, String status, LocalDateTime created,
                            LocalDateTime dispatched, LocalDateTime finished, long errorCode) {
        jdbc.update("insert into wcs_task(task_no,app_id,status,create_time,dispatch_time,finish_time,error_code)"
                        + " values (?,?,?,?,?,?,?)",
                no, appId, status, created, dispatched, finished, errorCode);
    }

    private void insertAlarm(long appId, long code, String level, String message, LocalDateTime raised) {
        jdbc.update("insert into wcs_alarm(app_id,fault_code,level,message,raised_time) values (?,?,?,?,?)",
                appId, code, level, message, raised);
    }

    private void insertLog(long appId, String code, String state, LocalDateTime at) {
        jdbc.update("insert into wcs_connection_log(app_id,app_code,event,state,create_time) values (?,?,?,?,?)",
                appId, code, "STATE_CHANGE", state, at);
    }

    private static long millis(LocalDateTime time) {
        return ReconcileSupport.toEpochMillis(time);
    }
}
