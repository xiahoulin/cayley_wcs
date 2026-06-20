package com.cayleywcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.cayleywcs.faultcode.FaultCodeResolver;
import com.cayleywcs.faultcode.FaultInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 冒烟：在 H2(PostgreSQL 模式) 上跑全部 Flyway 迁移，校验建表/种子 SQL 正确、上下文可加载、故障码解析两条路径。
 */
@SpringBootTest
@ActiveProfiles("test-flyway")
class SchemaSeedTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FaultCodeResolver faultCodeResolver;

    @Test
    void migrationsCreateAdminAndStackerSeed() {
        Integer adminUsers = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_user\" where \"user_num\" = 'admin'", Integer.class);
        Integer protocolTypes = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_dict_item\" where \"type_code\" = 'protocol_type'", Integer.class);
        Integer stacker = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_protocol\" where \"protocol_code\" = 'STACKER_STD'", Integer.class);
        Integer points = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_protocol_point\" where \"protocol_id\" = "
                        + "(select \"id\" from \"wcs_protocol\" where \"protocol_code\"='STACKER_STD')", Integer.class);
        Integer faults = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_fault_code\" where \"protocol_id\" = "
                        + "(select \"id\" from \"wcs_protocol\" where \"protocol_code\"='STACKER_STD')", Integer.class);
        Integer demoApp = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_application\" where \"app_key\" = 'ak_stacker_demo'", Integer.class);
        // V4：礁盘工业码垛机 OPC UA 应用 + 协议
        Integer palletApp = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_application\" where \"app_name\" = '礁盘工业码垛机'", Integer.class);
        Integer opcuaPoints = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_protocol_point\" where \"protocol_id\" = "
                        + "(select \"id\" from \"wcs_protocol\" where \"protocol_code\"='STACKER_OPCUA')", Integer.class);
        String opcuaAddr = jdbcTemplate.queryForObject(
                "select \"address\" from \"wcs_protocol_point\" where \"field_name\"='WCS_Heart' and \"protocol_id\" = "
                        + "(select \"id\" from \"wcs_protocol\" where \"protocol_code\"='STACKER_OPCUA')", String.class);

        assertThat(adminUsers).isEqualTo(1);
        assertThat(protocolTypes).isEqualTo(7); // opcua/modbus_tcp/s7/tcp/mqtt/http/sim
        assertThat(stacker).isEqualTo(1);
        assertThat(points).isEqualTo(27); // STACKER_STD：12 命令区 + 15 状态区（6.18 修订版）
        assertThat(faults).isEqualTo(48); // STACKER_STD 1-48
        assertThat(demoApp).isEqualTo(1);
        assertThat(palletApp).isEqualTo(1);
        assertThat(opcuaPoints).isEqualTo(27);          // OPC UA 协议点位克隆自 STACKER_STD
        assertThat(opcuaAddr).isEqualTo("ns=3;s=\"WCS_Task\".\"From_WCS\".\"WCS_Heart\""); // NodeId 寻址
    }

    @Test
    void faultResolverHitAndFallback() {
        Long protocolId = jdbcTemplate.queryForObject(
                "select \"id\" from \"wcs_protocol\" where \"protocol_code\" = 'STACKER_STD'", Long.class);

        FaultInfo known = faultCodeResolver.resolve(protocolId, 1);
        assertThat(known.known()).isTrue();
        assertThat(known.name()).contains("行走轴误差过大");

        FaultInfo unknown = faultCodeResolver.resolve(protocolId, 9999);
        assertThat(unknown.known()).isFalse();
        assertThat(unknown.message()).contains("9999");
    }
}
