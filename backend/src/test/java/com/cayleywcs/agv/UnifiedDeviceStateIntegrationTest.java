package com.cayleywcs.agv;

import com.cayleywcs.auth.*;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.simulator.DeviceSimRegistry;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "cayleywcs.telemetry.reconcile-ms=3600000","cayleywcs.telemetry.tick-ms=3600000","cayleywcs.telemetry.storage-tick-ms=3600000",
    "cayleywcs.task.engine-interval-ms=3600000","cayleywcs.task.recovery-interval-ms=3600000"})
@ActiveProfiles("test-flyway")
class UnifiedDeviceStateIntegrationTest {
    static final ReadOnlyProtocolSimulator SIM=new ReadOnlyProtocolSimulator();
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("cayleywcs.endpoints.unified-seer.host",()->"127.0.0.1");
        registry.add("cayleywcs.endpoints.unified-seer.port",()->SIM.seer.getLocalPort());
        registry.add("cayleywcs.endpoints.unified-bus.host",()->"127.0.0.1");
        registry.add("cayleywcs.endpoints.unified-bus.port",()->SIM.modbus.getLocalPort());
        registry.add("cayleywcs.endpoints.unified-bus.timeout-ms",()->200);
        registry.add("spring.datasource.url",()->System.getProperty("agv.test.datasource-url","jdbc:h2:mem:unified_state;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"));
        registry.add("spring.datasource.username",()->System.getProperty("agv.test.db-user","sa"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("AGV_TEST_DB_PASSWORD",""));
        registry.add("spring.datasource.driver-class-name",()->System.getProperty("agv.test.db-driver","org.h2.Driver"));
        registry.add("spring.data.redis.port",()->System.getProperty("agv.test.redis-port","6379"));
    }
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired SensorPollingService polling;
    @Autowired TelemetryBuffer buffer;
    @Autowired ConnectionManager connections;
    @Autowired ApplicationService applications;
    @Autowired ProtocolService protocols;
    @Autowired DeviceSimRegistry sims;
    final List<Long> opened=new ArrayList<>();
    @BeforeEach void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(1L,"admin","admin","admin",1L,1L),null,List.of()));}
    @AfterEach void cleanup(){for(Long id:opened)if(connections.isOpen(id))connections.close(id);polling.reconcile();SecurityContextHolder.clearContext();}
    @AfterAll static void closeServer(){SIM.close();}
    JsonNode call(long tenant,String path,Object body) throws Exception {
        return callAs(1L,tenant,path,body);
    }
    JsonNode callAs(long user,long tenant,String path,Object body) throws Exception {
        String token=jwt.generateToken(new JwtUser(user,"test","test","admin",tenant,1L),10);
        var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body());
    }
    JsonNode ok(String path,Object body) throws Exception {var response=call(1,path,body);assertThat(response.path("isSuccess").asBoolean()).as(response.toPrettyString()).isTrue();return response.path("data");}
    static void until(BooleanSupplier check,long timeout) throws Exception {
        long end=System.nanoTime()+timeout*1_000_000;
        while(System.nanoTime()<end){if(check.getAsBoolean())return;Thread.sleep(50);}
        assertThat(check.getAsBoolean()).isTrue();
    }
    JsonNode state(long id) throws Exception{return ok("/connection/state",Map.of("id",id));}
    JsonNode point(JsonNode state,String name){for(var p:state.path("points"))if(p.path("field").asText().equals(name))return p;throw new AssertionError(name);}
    Map<String,Object> setting(JsonNode state,String field,Object value){return Map.of("appId",state.path("appId").asLong(),"revisionId",state.path("revisionId").asLong(),"configurationEpoch",state.path("configurationEpoch").asLong(),"payload",Map.of(field,value));}
    void open(long id)throws Exception{ok("/connection/open",Map.of("id",id));opened.add(id);}

    @Test void separateSensorApplicationsShareBusAndPreserveQualityPermissionsAndHistory() throws Exception {
        long agv=ok("/agv/create",Map.of("appCode","UNIFIED_"+System.nanoTime(),"appName","统一采集测试车","endpointRef","unified-seer")).path("appId").asLong();
        open(agv);
        until(()->{try{return point(state(agv),"loc_x").path("quality").asText().equals("GOOD");}catch(Exception ex){throw new RuntimeException(ex);}},8000);
        var vehicle=state(agv);
        assertThat(vehicle.path("points").size()).isEqualTo(43);
        assertThat(point(vehicle,"loc_x").path("value").asDouble()).isEqualTo(1.25);
        assertThat(point(vehicle,"loc_confidence").path("quality").asText()).isEqualTo("UNKNOWN");
        assertThat(point(vehicle,"loc_confidence").path("value").isNull()).isTrue();
        assertThat(call(1,"/connection/state/set",setting(vehicle,"loc_x",5)).path("code").asInt()).isEqualTo(403);
        var gateway=ok("/telemetry/gateway/configure",Map.of("agvAppId",agv,"endpointRef","unified-bus"));
        long id=gateway.path("id").asLong();
        var links=ok("/telemetry/gateway/applications",Map.of("gatewayId",id));
        assertThat(links.size()).isEqualTo(3);
        assertThat(ok("/telemetry/gateway/applications",Map.of("gatewayId",id))).isEqualTo(links);
        assertThat(call(2,"/telemetry/gateway/applications",Map.of("gatewayId",id)).path("isSuccess").asBoolean()).isFalse();
        Map<String,Long> ids=new HashMap<>();
        for(var link:links) {
            long app=link.path("appId").asLong();String kind=link.path("sensorKind").asText();ids.put(kind,app);
            var before=state(app);
            assertThat(before.path("points").size()).isEqualTo(kind.equals("vibration_sensor")?10:1);
            for(var p:before.path("points")) {assertThat(p.path("value").isNull()).isTrue();assertThat(p.path("writable").asBoolean()).isFalse();assertThat(p.path("canSet").asBoolean()).isFalse();}
            assertThat(call(2,"/connection/state",Map.of("id",app)).path("isSuccess").asBoolean()).isFalse();
            open(app);
        }
        assertThat(call(1,"/telemetry/gateway/enable",Map.of("id",id,"enabled",true,"expectedVersion",1)).path("isSuccess").asBoolean()).isFalse();
        polling.reconcile();Thread.sleep(2050);polling.tick();
        until(()->"ONLINE".equals(polling.live(id).get("connectionState")),5000);
        long noise=ids.get("noise_sensor"),vibration=ids.get("vibration_sensor"),infrared=ids.get("infrared_sensor");
        assertThat(point(state(noise),"noise").path("value").asDouble()).isEqualTo(71.3);
        assertThat(point(state(vibration),"vibration_speed_x").path("value").asDouble()).isEqualTo(600.0);
        assertThat(point(state(infrared),"infrared_temperature").path("quality").asText()).isEqualTo("WARMUP_UNKNOWN");
        assertThat(point(state(infrared),"infrared_temperature").path("value").isNull()).isTrue();
        assertThat(SIM.reads).extracting(ReadOnlyProtocolSimulator.Request::connection).containsOnly(1);
        for(int i=1;i<SIM.reads.size();i++)assertThat(SIM.reads.get(i).time()-SIM.reads.get(i-1).time()).isGreaterThanOrEqualTo(240_000_000L);
        assertThat(call(1,"/connection/state/set",setting(state(noise),"noise",22)).path("code").asInt()).isEqualTo(403);
        assertThat(call(1,"/task/create",Map.of("app_id",noise,"task_no","FORBIDDEN_SENSOR","payload",Map.of())).path("isSuccess").asBoolean()).isFalse();
        buffer.flush();until(()->jdbc.queryForObject("select count(*) from wcs_telemetry_latest where gateway_id=?",Long.class,id)==12,5000);
        assertThat(ok("/telemetry/history",Map.of("gatewayId",id,"limit",10)).at("/rows/0/measurements/noise/raw").asInt()).isEqualTo(713);
        // Closing one logical application must leave the physical connection and other slaves running.
        ok("/connection/close",Map.of("id",noise));polling.reconcile();
        int offset=SIM.reads.size();
        until(()->{polling.tick();return SIM.reads.size()>=offset+3;},6000);
        until(()->{try{return point(state(vibration),"vibration_speed_x").path("quality").asText().equals("GOOD");}catch(Exception ex){return false;}},3000);
        assertThat(SIM.reads.subList(offset,SIM.reads.size())).noneMatch(r->r.unit()==1);
        assertThat(SIM.reads).extracting(ReadOnlyProtocolSimulator.Request::connection).containsOnly(1);
        assertThat(point(state(noise),"noise").path("value").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from wcs_task where app_id in (?,?,?,?)",Long.class,agv,noise,vibration,infrared)).isZero();
        ok("/connection/close",Map.of("id",vibration));ok("/connection/close",Map.of("id",infrared));polling.reconcile();
        assertThat(polling.live(id).get("connectionState")).isEqualTo("STOPPED");
    }

    @Test void writableDeviceChecksModePhysicalStateAndSelectedRevisionBeforeActualWrite() throws Exception {
        var p=protocols.listAll().stream().filter(r->r.getProtocol_code().equals("CONVEYOR_SIM")).findFirst().orElseThrow();
        if(!"published".equals(p.getLifecycle_state()))p=protocols.publish(p.getId(),p.getRow_version());
        var a=new ApplicationEntity();a.setApp_code("SET_"+System.nanoTime());a.setApp_name("状态设置测试输送线");a.setDirection("downstream");a.setDevice_type("conveyor");
        a.setConn_params(Map.of());a.setEnabled(true);a.setHeartbeat_interval_ms(500L);a=applications.create(a);
        p=protocols.cloneDraft(p.getId(),a.getId());p=protocols.publish(p.getId(),p.getRow_version());a.setProtocol_id(p.getId());a.setProtocol_revision_id(p.getPublished_revision_id());a=applications.update(a);long id=a.getId();open(id);
        until(()->{try{return point(state(id),"status_Task").path("quality").asText().equals("GOOD");}catch(Exception ex){return false;}},5000);
        var current=state(id);assertThat(point(current,"cmd_DestStation").path("writable").asBoolean()).isTrue();
        assertThat(point(current,"cmd_DestStation").path("canSet").asBoolean()).isFalse();
        assertThat(point(current,"cmd_DestStation").path("quality").asText()).isEqualTo("NO_READBACK");
        assertThat(call(1,"/connection/state/set",setting(current,"cmd_DestStation",3)).path("isSuccess").asBoolean()).isFalse();
        applications.updateWorkMode(id,"maintenance");current=state(id);
        assertThat(point(current,"cmd_DestStation").path("canSet").asBoolean()).isTrue();
        ok("/connection/state/set",setting(current,"cmd_DestStation",3));
        assertThat(((Number)sims.find(id).orElseThrow().get("cmd_DestStation")).longValue()).isEqualTo(3);
        assertThat(call(1,"/connection/state/set",setting(current,"status_Task",0)).path("isSuccess").asBoolean()).isFalse();
        var stale=new HashMap<>(setting(current,"cmd_DestStation",4));stale.put("configurationEpoch",-1);
        assertThat(call(1,"/connection/state/set",stale).path("code").asInt()).isEqualTo(409);
        assertThat(call(1,"/connection/state/set",setting(current,"cmd_DestStation",-5)).path("isSuccess").asBoolean()).isFalse();
        assertThat(((Number)sims.find(id).orElseThrow().get("cmd_DestStation")).longValue()).isEqualTo(3);
        sims.find(id).orElseThrow().set("status_Task",1);
        assertThat(call(1,"/connection/state/set",setting(current,"cmd_DestStation",4)).path("isSuccess").asBoolean()).isFalse();
        assertThat(((Number)sims.find(id).orElseThrow().get("cmd_DestStation")).longValue()).isEqualTo(3);
        assertThat(call(2,"/connection/state/set",setting(current,"cmd_DestStation",4)).path("isSuccess").asBoolean()).isFalse();
    }
    @Test void upgradingExistingReadOnlyApplicationPreservesIdentityAndPublishedHistory() throws Exception {
        var app=new ApplicationEntity();app.setApp_code("UPGRADE_"+System.nanoTime());app.setApp_name("旧车辆");app.setDirection("downstream");app.setDevice_type("agv");
        app.setAdapter_type("seer_agv");app.setEnabled(true);app.setConn_params(Map.of("endpointRef","unified-seer"));app.setHeartbeat_interval_ms(500L);app=applications.create(app);
        var protocol=new com.cayleywcs.protocol.entity.ProtocolEntity();protocol.setApp_id(app.getId());protocol.setProtocol_code("LEGACY_"+System.nanoTime());
        protocol.setProtocol_name("旧版仙工状态");protocol.setProtocol_type("tcp");protocol.setStatus("enabled");protocol.setData_format(Map.of());protocol.setTransport_template(Map.of());
        protocol=protocols.create(protocol);
        for(var field:com.cayleywcs.adapter.seer.SeerTcpAdapter.FIELDS.entrySet()) {
            var point=new com.cayleywcs.protocol.entity.ProtocolPointEntity();point.setProtocol_id(protocol.getId());point.setField_name(field.getKey());point.setSymbol_name(field.getKey());
            point.setTemplate_code("agv");point.setTemplate_version(1L);point.setDirection("response");point.setOperation("result");point.setPoint_scope("business");
            point.setLogical_type("STRING");point.setData_type("STRING");point.setAddress(field.getValue().toString());point.setRequired(true);point.setNullable(false);
            point.setScale(java.math.BigDecimal.ONE);point.setConstraints(Map.of("encoding","UTF-8","max_length",70000));protocols.createPoint(point);
        }
        protocol=protocols.getById(protocol.getId());protocol=protocols.publish(protocol.getId(),protocol.getRow_version());
        long oldProtocol=protocol.getId(),oldRevision=protocol.getPublished_revision_id();
        app.setProtocol_id(oldProtocol);app.setProtocol_revision_id(oldRevision);app=applications.update(app);long id=app.getId();open(id);
        until(()->{try{return ok("/agv/state",Map.of("appId",id)).path("quality").asText().equals("GOOD");}catch(Exception ex){return false;}},5000);
        ok("/agv/upgrade",Map.of("appId",id));
        until(()->{try{return point(state(id),"loc_x").path("quality").asText().equals("GOOD");}catch(Exception ex){return false;}},5000);
        var updated=applications.getById(id);
        assertThat(updated.getApp_key()).isEqualTo(app.getApp_key());assertThat(updated.getApp_code()).isEqualTo(app.getApp_code());
        assertThat(updated.getDesired_state()).isEqualTo("open");assertThat(updated.getProtocol_revision_id()).isNotEqualTo(oldRevision);
        assertThat(protocols.revisions(oldProtocol).getFirst().getPoints()).hasSize(3);
        long revision=updated.getProtocol_revision_id();ok("/agv/upgrade",Map.of("appId",id));
        assertThat(applications.getById(id).getProtocol_revision_id()).isEqualTo(revision);
    }

    @Test void dashboardUsesActualPointsAndPersistsSelectionsByUserWithoutChangingDeviceConfiguration() throws Exception {
        long id=ok("/agv/create",Map.of("appCode","BOARD_"+System.nanoTime(),"appName","真实点位看板","endpointRef","unified-seer")).path("appId").asLong();
        open(id);until(()->{try{return point(state(id),"loc_x").path("quality").asText().equals("GOOD");}catch(Exception ex){return false;}},5000);
        var app=applications.getById(id);
        assertThat(app.getAdapter_type()).isEqualTo("seer_agv");
        long epoch=jdbc.queryForObject("select configuration_epoch from wcs_application where id=?",Long.class,id);
        var initial=dashboard(ok("/dashboard/devices",Map.of()),id);
        assertThat(initial.path("protocolType").asText()).isEqualTo("tcp");
        assertThat(point(initial,"loc_current_station").path("label").asText()).isEqualTo("当前所在站点");
        assertThat(initial.path("points").toString()).doesNotContain("PLC_Heart");
        var selection=Map.of("appId",id,"revisionId",app.getProtocol_revision_id(),"fields",List.of("loc_x","nav_task_status"));
        ok("/dashboard/selection",selection);
        assertThat(dashboard(ok("/dashboard/devices",Map.of()),id).path("selectedFields").toString()).isEqualTo("[\"loc_x\",\"nav_task_status\"]");
        var other=dashboard(callAs(2,1,"/dashboard/devices",Map.of()).path("data"),id);
        assertThat(other.path("selectionConfigured").asBoolean()).isFalse();
        assertThat(callAs(2,1,"/dashboard/selection",Map.of("appId",id,"revisionId",app.getProtocol_revision_id(),"fields",List.of())).path("isSuccess").asBoolean()).isTrue();
        assertThat(dashboard(callAs(2,1,"/dashboard/devices",Map.of()).path("data"),id).path("selectedFields")).isEmpty();
        assertThat(dashboard(ok("/dashboard/devices",Map.of()),id).path("selectedFields")).hasSize(2);
        assertThat(call(2,"/dashboard/selection",selection).path("isSuccess").asBoolean()).isFalse();
        assertThat(call(1,"/dashboard/selection",Map.of("appId",id,"revisionId",-1,"fields",List.of("loc_x"))).path("code").asInt()).isEqualTo(409);
        assertThat(call(1,"/dashboard/selection",Map.of("appId",id,"revisionId",app.getProtocol_revision_id(),"fields",List.of("PLC_Heart"))).path("isSuccess").asBoolean()).isFalse();
        assertThat(call(1,"/dashboard/selection",Map.of("appId",id,"revisionId",app.getProtocol_revision_id(),"fields",List.of("loc_x","loc_x"))).path("isSuccess").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select configuration_epoch from wcs_application where id=?",Long.class,id)).isEqualTo(epoch);
        assertThat(applications.getById(id).getConn_params()).isEqualTo(app.getConn_params());
        assertThat(SIM.seerApis).allMatch(api->Set.of(1004,1005,1020).contains(api));
    }
    private JsonNode dashboard(JsonNode rows,long id){for(var row:rows)if(row.path("appId").asLong()==id)return row;throw new AssertionError("dashboard missing "+id);}
}
