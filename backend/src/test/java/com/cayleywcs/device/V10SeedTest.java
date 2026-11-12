package com.cayleywcs.device;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V10 迁移在 H2 上落地校验：输送线/电梯/自动门三套 sim 协议 + 点位 + 故障码 + 应用（含 device_type）。
 */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:cayleywcs_seed_v10;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test-flyway")
class V10SeedTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void newDeviceSeedsPresent() {
        assertThat(protocolCount("CONVEYOR_SIM")).isEqualTo(1);
        assertThat(protocolCount("LIFT_SIM")).isEqualTo(1);
        assertThat(protocolCount("DOOR_SIM")).isEqualTo(1);

        assertThat(pointCount("CONVEYOR_SIM")).isEqualTo(12);
        assertThat(pointCount("LIFT_SIM")).isEqualTo(12);
        assertThat(pointCount("DOOR_SIM")).isEqualTo(8);

        assertThat(deviceType("CONVEYOR_SIM")).isEqualTo("conveyor");
        assertThat(deviceType("LIFT_SIM")).isEqualTo("lift");
        assertThat(deviceType("DOOR_SIM")).isEqualTo("auto_door");

        // V9 存量订正：码垛机应用归 palletizer
        String palletType = jdbcTemplate.queryForObject(
                "select \"device_type\" from \"wcs_application\" where \"app_code\" = 'PALLET01'", String.class);
        assertThat(palletType).isEqualTo("palletizer");

        // device_type 字典就位
        Integer dt = jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_dict_item\" where \"type_code\" = 'device_type'", Integer.class);
        assertThat(dt).isEqualTo(13);
        assertThat(jdbcTemplate.queryForList("select item_code from wcs_dict_item where type_code='device_type'",String.class))
                .contains("vibration_sensor","noise_sensor","infrared_sensor");
    }

    private Integer protocolCount(String code) {
        return jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_protocol\" where \"protocol_code\" = ?", Integer.class, code);
    }

    private Integer pointCount(String protocolCode) {
        return jdbcTemplate.queryForObject(
                "select count(*) from \"wcs_protocol_point\" where \"protocol_id\" = "
                        + "(select \"id\" from \"wcs_protocol\" where \"protocol_code\" = ?)", Integer.class, protocolCode);
    }

    private String deviceType(String protocolCode) {
        return jdbcTemplate.queryForObject(
                "select \"device_type\" from \"wcs_application\" where \"protocol_id\" = "
                        + "(select \"id\" from \"wcs_protocol\" where \"protocol_code\" = ?)", String.class, protocolCode);
    }
}
