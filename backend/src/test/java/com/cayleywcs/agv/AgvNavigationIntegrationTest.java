package com.cayleywcs.agv;

import com.cayleywcs.auth.*;
import com.cayleywcs.connection.ConnectionManager;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
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
        "cayleywcs.task.engine-interval-ms=3600000","cayleywcs.task.recovery-interval-ms=3600000"})
@ActiveProfiles("test-flyway")
class AgvNavigationIntegrationTest {
    static final SeerNavigationSimulator SIM=new SeerNavigationSimulator();
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("cayleywcs.endpoints.nav-test.host",()->"127.0.0.1");
        registry.add("cayleywcs.endpoints.nav-test.port",()->SIM.state.getLocalPort());
        registry.add("spring.datasource.url",()->System.getProperty("nav.test.datasource-url","jdbc:h2:mem:navigation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"));
        registry.add("spring.datasource.username",()->System.getProperty("nav.test.db-user","sa"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("NAV_TEST_DB_PASSWORD",""));
        registry.add("spring.datasource.driver-class-name",()->System.getProperty("nav.test.db-driver","org.h2.Driver"));
    }
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired ConnectionManager connections;
    @Autowired JdbcTemplate jdbc;
    final List<Long> opened=new ArrayList<>();
    @BeforeEach void reset() {
        SIM.mode="ok";SIM.moving=false;SIM.commands.clear();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(1L,"admin","admin","admin",1L,1L),null,List.of()));
    }
    @AfterEach void cleanup() {for(var id:opened)if(connections.isOpen(id))connections.close(id);SecurityContextHolder.clearContext();}
    @AfterAll static void close(){SIM.close();}
    JsonNode call(long tenant,String role,String path,Object body)throws Exception {
        String token=jwt.generateToken(new JwtUser(1L,"tester","tester",role,tenant,1L),10);
        var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body());
    }
    JsonNode ok(String path,Object body)throws Exception {
        var reply=call(1,"admin",path,body);assertThat(reply.path("isSuccess").asBoolean()).as(reply.toString()).isTrue();return reply.path("data");
    }
    long create()throws Exception {
        long id=ok("/agv/create",Map.of("appCode","NAV_TEST_"+System.nanoTime(),"appName","导航测试车","endpointRef","nav-test")).path("appId").asLong();
        var app=(ObjectNode)ok("/application/detail",Map.of("id",id));
        ((ObjectNode)app.path("conn_params")).put("ip","127.0.0.1").put("navigationPort",SIM.navigation.getLocalPort()).put("timeoutMs",250);
        ok("/application/update",app);ok("/connection/open",Map.of("id",id));opened.add(id);return id;
    }
    Map<String,Object> command(long id,String requestId)throws Exception {
        var s=ok("/agv/navigation/state",Map.of("appId",id));
        return Map.of("appId",id,"revisionId",s.path("revisionId").asLong(),"configurationEpoch",s.path("configurationEpoch").asLong(),"requestId",requestId,"stationName","测试站点");
    }
    @Test void ipAndPublishedNavigationOperateThroughHttpWithDurableDedupAndPermissions()throws Exception {
        long id=create();var before=ok("/application/detail",Map.of("id",id));
        var reconnect=(ObjectNode)before.deepCopy();((ObjectNode)reconnect.path("conn_params")).put("timeoutMs",400);
        assertThat(ok("/application/update",reconnect).path("desired_state").asText()).isEqualTo("open");
        assertThat(ok("/connection/state",Map.of("id",id)).path("state").asText()).isEqualTo("RUNNING");
        var changed=(ObjectNode)before.deepCopy();((ObjectNode)changed.path("conn_params")).put("ip","127.0.0.2");
        // Inactive configuration switch and reconnect are tested without contacting any real network.
        ok("/connection/close",Map.of("id",id));ok("/application/update",changed);
        assertThat(ok("/application/detail",Map.of("id",id)).path("conn_params").path("ip").asText()).isEqualTo("127.0.0.2");
        ok("/application/update",before);ok("/connection/open",Map.of("id",id));
        var enabled=ok("/agv/navigation/enable",Map.of("appId",id));
        assertThat(enabled.path("navigationEnabled").asBoolean()).isTrue();
        assertThat(ok("/agv/upgrade",Map.of("appId",id)).path("motionEnabled").asBoolean()).isTrue();
        assertThat(ok("/agv/navigation/enable",Map.of("appId",id)).path("revisionId")).isEqualTo(enabled.path("revisionId"));
        var points=ok("/connection/state",Map.of("id",id)).path("points");assertThat(points.size()).isEqualTo(45);
        var cmd=command(id,"request-success");var result=ok("/agv/navigation/send",cmd);
        assertThat(result.path("sent").asBoolean()).isTrue();assertThat(result.path("status").asText()).isEqualTo("accepted");
        assertThat(SIM.commands).containsExactly(Map.of("id","测试站点"));
        assertThat(ok("/agv/navigation/send",cmd)).isEqualTo(result);assertThat(SIM.commands).hasSize(1);
        assertThat(call(2,"admin","/agv/navigation/send",cmd).path("code").asInt()).isEqualTo(404);
        assertThat(call(1,"viewer","/agv/navigation/send",cmd).path("code").asInt()).isEqualTo(403);
        assertThat(call(1,"admin","/agv/navigation/send",command(id,"request-blocked")).path("code").asInt()).isEqualTo(409);
        var duplicate=new HashMap<>(cmd);duplicate.put("stationName","AP0");
        assertThat(call(1,"admin","/agv/navigation/send",duplicate).path("code").asInt()).isEqualTo(409);
        assertThat(call(1,"admin","/application/update",before).path("code").asInt()).isEqualTo(409);
        SIM.moving=true;
        assertThat(call(1,"admin","/agv/navigation/acknowledge",Map.of("appId",id,"requestId","request-success")).path("code").asInt()).isEqualTo(409);
        SIM.moving=false;
        assertThat(ok("/agv/navigation/acknowledge",Map.of("appId",id,"requestId","request-success")).path("needsAcknowledgement").asBoolean()).isFalse();
        SIM.mode="reject";assertThat(ok("/agv/navigation/send",command(id,"request-rejected")).path("sent").asBoolean()).isFalse();
        assertThat(SIM.commands).hasSize(2);
        ok("/connection/close",Map.of("id",id));
        assertThat(call(1,"admin","/application/delete",Map.of("id",id)).path("code").asInt()).isEqualTo(409);
    }
    @Test void uncertainResponseAndInterruptedProcessRemainFencedAcrossReconnection()throws Exception {
        long id=create();ok("/agv/navigation/enable",Map.of("appId",id));SIM.mode="timeout";
        var cmd=command(id,"request-timeout");var result=ok("/agv/navigation/send",cmd);
        assertThat(result.path("status").asText()).isEqualTo("unknown");assertThat(result.path("sent").asBoolean()).isFalse();
        ok("/connection/reconnect",Map.of("id",id));
        assertThat(ok("/agv/navigation/send",cmd)).isEqualTo(result);assertThat(SIM.commands).hasSize(1);
        // Represents the durable state left by termination after admission and before response persistence.
        jdbc.update("update wcs_agv_navigation_command set status='sending' where app_id=?",id);
        assertThat(ok("/agv/navigation/send",cmd).path("status").asText()).isEqualTo("sending");
        assertThat(SIM.commands).hasSize(1);
        assertThat(call(1,"admin","/agv/navigation/send",command(id,"request-new-one")).path("code").asInt()).isEqualTo(409);
        ok("/agv/navigation/acknowledge",Map.of("appId",id,"requestId","request-timeout"));
    }
    @Test void movingVehicleBadIpAndStaleRevisionNeverSend()throws Exception {
        long id=create();ok("/agv/navigation/enable",Map.of("appId",id));
        var app=(ObjectNode)ok("/application/detail",Map.of("id",id));((ObjectNode)app.path("conn_params")).put("ip","invalid");
        assertThat(call(1,"admin","/application/update",app).path("code").asInt()).isEqualTo(4223);
        var cmd=new HashMap<>(command(id,"request-moving"));SIM.moving=true;
        assertThat(call(1,"admin","/agv/navigation/send",cmd).path("code").asInt()).isEqualTo(409);
        SIM.moving=false;cmd.put("configurationEpoch",-1);
        assertThat(call(1,"admin","/agv/navigation/send",cmd).path("code").asInt()).isEqualTo(409);
        assertThat(SIM.commands).isEmpty();
    }
}
