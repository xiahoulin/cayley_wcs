package com.cayleywcs.agv;

import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.adapter.seer.SeerQuerySession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in three-query check against the explicitly configured vehicle; never opens a motion port. */
@EnabledIfEnvironmentVariable(named="WCS_TEST_SEER_HOST",matches=".+")
class SeerLiveReadOnlyTest {
    @Test void readsExistingVehicleWithoutMotionCommands() throws Exception {
        var endpoint=new DeviceEndpoints.Endpoint(System.getenv("WCS_TEST_SEER_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("WCS_TEST_SEER_PORT","19204")),2000,
                Boolean.parseBoolean(System.getenv().getOrDefault("WCS_TEST_SEER_ALLOW_MISSING_RET_CODE","false")));
        var received=new ArrayList<Map<String,Object>>();
        try(var seer=new SeerQuerySession(endpoint)) {
            for(int api:List.of(1004,1005,1020)) received.add(seer.query(api));
        }
        assertThat(received).hasSize(3);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/agv-live-readonly.json").toFile(),received);
    }
}
