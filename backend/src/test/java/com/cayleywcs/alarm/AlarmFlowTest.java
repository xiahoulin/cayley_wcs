package com.cayleywcs.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import com.cayleywcs.connection.ConnectionState;
import com.cayleywcs.connection.event.ConnectionStateChangedEvent;
import com.cayleywcs.connection.event.FaultClearedEvent;
import com.cayleywcs.connection.event.FaultDetectedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 报警联动（M5）：故障码事件→查故障表→产生报警(未维护走兜底)；故障恢复/连接恢复→清除。
 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class AlarmFlowTest {

    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deviceFaultRaisesAndClearsAlarm() {
        Long protocolId = jdbcTemplate.queryForObject(
                "select \"id\" from \"wcs_protocol\" where \"protocol_code\" = 'STACKER_STD'", Long.class);
        long appId = 90001L;

        publisher.publishEvent(new FaultDetectedEvent(appId, protocolId, 8)); // 里库位检测1触发
        assertThat(alarmService.listActive(appId)).hasSize(1);
        assertThat(alarmService.listActive(appId).get(0).getMessage()).contains("里库位检测1触发");

        // 同码重复不新增（去重）
        publisher.publishEvent(new FaultDetectedEvent(appId, protocolId, 8));
        assertThat(alarmService.listActive(appId)).hasSize(1);

        // 故障恢复 -> 清除
        publisher.publishEvent(new FaultDetectedEvent(appId, protocolId, 0));
        assertThat(alarmService.listActive(appId)).isEmpty();
    }

    @Test
    void partialRecoveryClearsOnlyResolvedCode() {
        Long protocolId = jdbcTemplate.queryForObject(
                "select \"id\" from \"wcs_protocol\" where \"protocol_code\" = 'STACKER_STD'", Long.class);
        long appId = 90004L;

        // 两个故障码并发
        publisher.publishEvent(new FaultDetectedEvent(appId, protocolId, 2));
        publisher.publishEvent(new FaultDetectedEvent(appId, protocolId, 5));
        assertThat(alarmService.listActive(appId)).hasSize(2);

        // 单码 5 恢复 → 仅清 5，码 2 仍挂（部分恢复不再残留幽灵报警）
        publisher.publishEvent(new FaultClearedEvent(appId, protocolId, 5));
        var active = alarmService.listActive(appId);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getFault_code()).isEqualTo(2L);

        // 码 2 也恢复 → 全清
        publisher.publishEvent(new FaultClearedEvent(appId, protocolId, 2));
        assertThat(alarmService.listActive(appId)).isEmpty();
    }

    @Test
    void unmaintainedCodeUsesFallback() {
        Long protocolId = jdbcTemplate.queryForObject(
                "select \"id\" from \"wcs_protocol\" where \"protocol_code\" = 'STACKER_STD'", Long.class);
        long appId = 90002L;

        publisher.publishEvent(new FaultDetectedEvent(appId, protocolId, 9999));
        assertThat(alarmService.listActive(appId)).hasSize(1);
        assertThat(alarmService.listActive(appId).get(0).getMessage()).contains("9999");
    }

    @Test
    void connectionDropRaisesCommAlarmAndRecoveryClears() {
        long appId = 90003L;
        publisher.publishEvent(new ConnectionStateChangedEvent(appId, "APP3",
                ConnectionState.RUNNING, ConnectionState.DISCONNECTED, "stale"));
        assertThat(alarmService.listActive(appId)).hasSize(1);
        assertThat(alarmService.listActive(appId).get(0).getFault_code()).isEqualTo(-1L);

        publisher.publishEvent(new ConnectionStateChangedEvent(appId, "APP3",
                ConnectionState.RECONNECTING, ConnectionState.RUNNING, "reconnected"));
        assertThat(alarmService.listActive(appId)).isEmpty();
    }
}
