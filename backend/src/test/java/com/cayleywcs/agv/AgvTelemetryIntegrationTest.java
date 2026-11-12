package com.cayleywcs.agv;

import com.cayleywcs.auth.JwtService;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.telemetry.polling.SensorPollingService;
import com.cayleywcs.telemetry.storage.TelemetryBuffer;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "cayleywcs.telemetry.reconcile-ms=3600000","cayleywcs.telemetry.tick-ms=3600000",
    "cayleywcs.telemetry.storage-tick-ms=3600000"})
@ActiveProfiles("test-flyway")
class AgvTelemetryIntegrationTest {
    static final ReadOnlyProtocolSimulator SIM=new ReadOnlyProtocolSimulator();
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("cayleywcs.endpoints.test-seer.host",()->"127.0.0.1");
        registry.add("cayleywcs.endpoints.test-seer.port",()->SIM.seer.getLocalPort());
        registry.add("cayleywcs.endpoints.test-bus.host",()->"127.0.0.1");
        registry.add("cayleywcs.endpoints.test-bus.port",()->SIM.modbus.getLocalPort());
        registry.add("cayleywcs.endpoints.test-bus.timeout-ms",()->200);
        registry.add("spring.datasource.url",()->System.getProperty("agv.test.datasource-url",
                "jdbc:h2:mem:agv_telemetry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"));
        registry.add("spring.datasource.username",()->System.getProperty("agv.test.db-user","sa"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("AGV_TEST_DB_PASSWORD",""));
        registry.add("spring.datasource.driver-class-name",()->System.getProperty("agv.test.db-driver","org.h2.Driver"));
        registry.add("spring.data.redis.port",()->System.getProperty("agv.test.redis-port","6379"));
        if(System.getenv("WCS_TEST_SEER_HOST")!=null) {
            registry.add("cayleywcs.endpoints.existing-seer.host",()->System.getenv("WCS_TEST_SEER_HOST"));
            registry.add("cayleywcs.endpoints.existing-seer.port",()->Integer.parseInt(System.getenv().getOrDefault("WCS_TEST_SEER_PORT","19204")));
        }
    }
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired SensorPollingService polling;
    @Autowired TelemetryBuffer buffer;
    @AfterAll static void closeServer(){SIM.close();}
    JsonNode call(long tenant,String path,Object body) throws Exception {
        String token=jwt.generateToken(new JwtUser(1L,"agv-test","agv-test","admin",tenant,1L),10);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
    JsonNode ok(String path,Object body) throws Exception {
        JsonNode response=call(1,path,body);
        assertThat(response.path("isSuccess").asBoolean()).as(response.toPrettyString()).isTrue();
        return response.path("data");
    }
    static void until(BooleanSupplier check,long timeout) throws Exception {
        long deadline=System.nanoTime()+timeout*1_000_000;
        while(System.nanoTime()<deadline){if(check.getAsBoolean())return;Thread.sleep(50);}
        assertThat(check.getAsBoolean()).isTrue();
    }
    @Test void actualHttpToTcpCollectionPersistsTwelveMetricsAndEnforcesReadOnlyAndOwnership() throws Exception {
        long app=ok("/agv/create",Map.of("appCode","SEER_"+System.nanoTime(),"appName","协议模拟测试车","endpointRef","test-seer")).path("appId").asLong();
        long gateway=0;
        try {
            ok("/connection/open",Map.of("id",app));
            until(()->{try{return ok("/agv/state",Map.of("appId",app)).path("quality").asText().equals("GOOD");}catch(Exception ex){throw new RuntimeException(ex);}},8000);
            JsonNode state=ok("/agv/state",Map.of("appId",app));
            assertThat(state.path("queries").path("seer_location").path("payload").path("x").asDouble()).isEqualTo(1.25);
            assertThat(state.path("motionEnabled").asBoolean()).isFalse();
            assertThat(call(1,"/task/create",Map.of("app_id",app,"task_no","NO_MOTION","payload",Map.of("station","AP0"))).path("isSuccess").asBoolean()).isFalse();
            assertThat(call(1,"/connection/write",Map.of("appId",app,"payload",Map.of("seer_location","x"))).path("isSuccess").asBoolean()).isFalse();
            assertThat(call(2,"/agv/state",Map.of("appId",app)).path("isSuccess").asBoolean()).isFalse();
            assertThat(call(2,"/agv/create",Map.of("appCode","STEAL_ENDPOINT","appName","other tenant","endpointRef","test-seer")).path("isSuccess").asBoolean()).isFalse();
            JsonNode config=ok("/telemetry/gateway/configure",Map.of("agvAppId",app,"endpointRef","test-bus","infraredOffset",1,"infraredMin",0,"infraredMax",600));
            gateway=config.path("id").asLong();long id=gateway;
            config=ok("/telemetry/gateway/enable",Map.of("id",id,"enabled",true,"expectedVersion",config.path("config_version").asLong()));
            polling.reconcile();Thread.sleep(2050);polling.tick();
            until(()->"ONLINE".equals(polling.live(id).get("connectionState")),5000);
            JsonNode latest=ok("/telemetry/latest",Map.of("gatewayId",id));
            assertThat(latest.path("measurements").size()).isEqualTo(12);
            assertThat(latest.at("/measurements/vibration_speed_x/value").asDouble()).isEqualTo(600.0);
            assertThat(latest.at("/measurements/noise/value").asDouble()).isEqualTo(71.3);
            assertThat(latest.at("/measurements/infrared_temperature/value").asDouble()).isEqualTo(20.0);
            assertThat(latest.at("/measurements/infrared_temperature/quality").asText()).isEqualTo("WARMUP_UNKNOWN");
            assertThat(latest.at("/measurements/noise/context/quality").asText()).isEqualTo("GOOD");
            buffer.flush();
            until(()->jdbc.queryForObject("select count(*) from wcs_telemetry_latest where gateway_id=?",Long.class,id)==12,5000);
            JsonNode history=ok("/telemetry/history",Map.of("gatewayId",id,"limit",10));
            assertThat(history.path("rows").size()).isGreaterThanOrEqualTo(1);
            assertThat(history.at("/rows/0/measurements/noise/raw").asInt()).isEqualTo(713);
            assertThat(call(2,"/telemetry/latest",Map.of("gatewayId",id)).path("isSuccess").asBoolean()).isFalse();
            assertThat(call(2,"/telemetry/history",Map.of("gatewayId",id,"limit",10)).path("isSuccess").asBoolean()).isFalse();
            assertThat(call(2,"/telemetry/gateway/enable",Map.of("id",id,"enabled",false,"expectedVersion",2)).path("isSuccess").asBoolean()).isFalse();
            assertThat(ok("/telemetry/gateway/list",Map.of("agvAppId",app)).size()).isEqualTo(1);
            assertThat(call(1,"/telemetry/gateway/enable",Map.of("id",id,"enabled",true,"expectedVersion",1)).path("isSuccess").asBoolean()).isFalse();
            // A verified continuous supply record, not a network reconnection, establishes warmup.
            config=ok("/telemetry/gateway/power",Map.of("id",id,"powerOnAt",System.currentTimeMillis()-41*60*1000L,
                    "evidence","模拟电源事件","expectedVersion",config.path("config_version").asLong()));
            polling.reconcile();Thread.sleep(2050);polling.tick();
            until(()->"ONLINE".equals(polling.live(id).get("connectionState")),5000);
            latest=ok("/telemetry/latest",Map.of("gatewayId",id));
            assertThat(latest.at("/measurements/infrared_temperature/quality").asText()).isEqualTo("GOOD");
            buffer.flush();until(()->((Number)buffer.status(id).get("queued")).intValue()==0,5000);
            // Failure leaves last-good separately and aborts remaining blocks, without retrying the same cycle.
            SIM.silence.set(true);Thread.sleep(2050);polling.tick();
            until(()->"DEGRADED".equals(polling.live(id).get("connectionState")),5000);
            latest=ok("/telemetry/latest",Map.of("gatewayId",id));
            assertThat(latest.at("/measurements/vibration_speed_x/value").isNull()).isTrue();
            assertThat(latest.at("/measurements/vibration_speed_x/quality").asText()).isEqualTo("TIMEOUT");
            assertThat(latest.at("/measurements/vibration_speed_x/lastGood/value").asDouble()).isEqualTo(600.0);
            assertThat(latest.at("/measurements/noise/quality").asText()).isEqualTo("UNKNOWN");
            Thread.sleep(6100);
            assertThat(ok("/telemetry/latest",Map.of("gatewayId",id)).at("/measurements/vibration_speed_x/quality").asText()).isEqualTo("STALE");
            SIM.silence.set(false);polling.tick();
            until(()->"ONLINE".equals(polling.live(id).get("connectionState")),5000);
            assertThat(ok("/telemetry/latest",Map.of("gatewayId",id)).at("/measurements/infrared_temperature/quality").asText()).isEqualTo("GOOD");
            int previousNoise=(int)SIM.reads.stream().filter(read->read.unit()==1).count();
            SIM.silentUnit.set(80);
            until(()->{polling.tick();return SIM.reads.stream().filter(read->read.unit()==1).count()>previousNoise;},8000);
            until(()->{try{return "GOOD".equals(ok("/telemetry/latest",Map.of("gatewayId",id)).at("/measurements/noise/quality").asText());}catch(Exception ex){throw new RuntimeException(ex);}},3000);
            SIM.silentUnit.set(0);
            assertThat(SIM.seerApis).allMatch(api->Set.of(1004,1005,1020).contains(api));
            assertThat(SIM.reads).allMatch(read->Set.of(80,1,2).contains(read.unit()));
            assertThat(jdbc.queryForObject("select count(*) from wcs_task where app_id=?",Long.class,app)).isZero();
            config=ok("/telemetry/gateway/enable",Map.of("id",id,"enabled",false,"expectedVersion",config.path("config_version").asLong()));
            config=ok("/telemetry/gateway/configure",Map.of("id",id,"agvAppId",app,"endpointRef","replacement-sensors",
                    "expectedVersion",config.path("config_version").asLong()));
            assertThat(config.path("power_on_at").isNull()).isTrue();
            assertThat(config.path("power_evidence").isNull()).isTrue();
        } finally {
            SIM.silence.set(false); SIM.silentUnit.set(0);
            if(gateway!=0){jdbc.update("update wcs_sensor_gateway set enabled=false where id=?",gateway);polling.stop(gateway);}
            ok("/connection/close",Map.of("id",app));
        }
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="WCS_TEST_SEER_HOST",matches=".+")
    void existingVehicleReadsThroughWcsAndDefaultsRemainDormantWithoutGatewayAddress() throws Exception {
        long app=ok("/agv/create",Map.of("appCode","LIVE_SEER_"+System.nanoTime(),"appName","现有仙工只读验收","endpointRef","existing-seer")).path("appId").asLong();
        try {
            var gateway=ok("/telemetry/gateway/configure",Map.of("agvAppId",app,"endpointRef","existing-seer-sensors"));
            assertThat(gateway.path("infrared_offset").asInt()).isZero();
            assertThat(gateway.path("infrared_min").asDouble()).isZero();
            assertThat(gateway.path("infrared_max").asDouble()).isEqualTo(600);
            assertThat(gateway.path("enabled").asBoolean()).isFalse();
            var sensors=ok("/telemetry/latest",Map.of("gatewayId",gateway.path("id").asLong()));
            assertThat(sensors.path("endpointConfigured").asBoolean()).isFalse();
            assertThat(sensors.at("/measurements/noise/quality").asText()).isEqualTo("UNKNOWN");
            ok("/connection/open",Map.of("id",app));
            until(()->{try{return "GOOD".equals(ok("/agv/state",Map.of("appId",app)).path("quality").asText());}catch(Exception ex){throw new RuntimeException(ex);}},10000);
            var state=ok("/agv/state",Map.of("appId",app));
            assertThat(state.path("queries").size()).isEqualTo(3);
            assertThat(state.at("/queries/seer_location/payload/ret_code").asInt()).isZero();
            JSON.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/agv-live-wcs-state.json").toFile(),state);
        } finally { ok("/connection/close",Map.of("id",app)); }
    }
}
