package com.cayleywcs.task;

import static org.assertj.core.api.Assertions.*;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.auth.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.cayleywcs.binding.BindingService;
import com.cayleywcs.binding.entity.AppBindingEntity;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.simulator.DeviceSim;
import com.cayleywcs.simulator.DeviceSimRegistry;
import com.cayleywcs.task.entity.TaskEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** Actual committed profiles, bindings, SimAdapter I/O, state machines and task/result persistence; no mocked services. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:payload_execution;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "cayleywcs.task.engine-interval-ms=1000000000","cayleywcs.chain.orchestrate-interval-ms=1000000000",
        "cayleywcs.task.recovery-interval-ms=1000000000","cayleywcs.connection.recovery-reconcile-interval-ms=1000000000",
        "cayleywcs.connection.max-connections=64","cayleywcs.connection.heartbeat-stale-ms=1000000000"})
@ActiveProfiles("test-flyway")
class TaskPayloadExecutionIntegrationTest {
    @Autowired ProtocolService protocols;
    @Autowired ApplicationService apps;
    @Autowired BindingService bindings;
    @Autowired ConnectionManager connections;
    @Autowired DeviceSimRegistry sims;
    @Autowired TaskService tasks;
    @Autowired TaskEngine engine;
    @Autowired TaskExecutionService execution;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @LocalServerPort int port;
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    final List<Long> opened=new ArrayList<>();
    record Fixture(ApplicationEntity up,ApplicationEntity down,AppBindingEntity binding,ProtocolEntity profile) { }

    @BeforeEach void authenticate() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(1L,"admin","admin","admin",1L,1L),null,List.of())); }
    @AfterEach void close() { for(Long id:opened)if(connections.isOpen(id))connections.close(id);opened.clear();SecurityContextHolder.clearContext(); }
    ProtocolEntity protocol(String code) { return protocols.listAll().stream().filter(p->code.equals(p.getProtocol_code())).findFirst().orElseThrow(); }
    ProtocolEntity publish(ProtocolEntity p) { return "published".equals(p.getLifecycle_state())?p:protocols.publish(p.getId(),p.getRow_version()); }
    ApplicationEntity app(ProtocolEntity p,String direction,String type) {
        ApplicationEntity a=new ApplicationEntity();a.setApp_code("execution-"+UUID.randomUUID());a.setApp_name("点位业务仿真");a.setDirection(direction);a.setDevice_type(type);
        a.setEnabled(true);a.setConn_params(Map.of());a.setMax_retry(1L);a.setHeartbeat_interval_ms(1000000000L);
        a=apps.create(a);var copy=protocols.cloneDraft(p.getId(),a.getId());copy=protocols.publish(copy.getId(),copy.getRow_version());a.setProtocol_id(copy.getId());a.setProtocol_revision_id(copy.getPublished_revision_id());return apps.update(a);
    }
    Fixture fixture(String type) { return fixture(type,null); }
    Fixture fixture(String type,DeviceSim replacement) {
        ProtocolEntity http=publish(protocol("HTTP_DEVICE_CONTRACT")); ProtocolEntity device;
        if("stacker".equals(type)) { device=protocols.cloneDraft(protocol("PALLET_SIM_OPCUA").getId());device.setProtocol_type("sim");device=protocols.update(device);device=publish(device); }
        else device=publish(protocol(switch(type){case "conveyor"->"CONVEYOR_SIM";case "lift"->"LIFT_SIM";case "auto_door"->"DOOR_SIM";default->throw new IllegalArgumentException();}));
        ApplicationEntity up=app(http,"upstream","none"),down=app(device,"downstream",type);
        AppBindingEntity row=new AppBindingEntity();row.setUpstream_app_id(up.getId());row.setDownstream_app_id(down.getId());row=bindings.create(row);
        if(replacement!=null)sims.getOrCreate(down.getId(),()->replacement);
        connections.open(down.getId());opened.add(down.getId());return new Fixture(up,down,row,protocols.getById(down.getProtocol_id()));
    }
    static Map<String,Object> payload(String type) {
        return switch(type) {
            case "stacker"->Map.of("cmd_TaskType",3,"cmd_TakeCoor_Row",1,"cmd_TakeCoor_Column",1,"cmd_TakeCoor_Floor",1,"cmd_PutCoor_Row",1,"cmd_PutCoor_Column",2,"cmd_PutCoor_Floor",3,"cmd_PortNum",1);
            case "conveyor"->Map.of("cmd_DestStation",3);
            case "lift"->Map.of("cmd_TargetFloor",2);
            case "auto_door"->Map.of("cmd_Open",1,"cmd_Close",0);
            default->throw new IllegalArgumentException();
        };
    }
    TaskEntity dispatch(Fixture f,String no,Map<String,Object> payload) { return tasks.dispatch(new TaskService.DispatchRequest(f.down().getId(),no,0L,"TEST-2025",payload),f.up().getId()); }
    DeviceSim device(Fixture f) { return sims.find(f.down().getId()).orElseThrow(); }
    TaskEntity complete(Fixture f,TaskEntity t) {
        for(int i=0;i<24;i++) {
            device(f).tick();engine.advanceApp(f.down().getId(),f.down().getDevice_type());
            t=tasks.getById(t.getId());
            if(TaskStatus.COMPLETED.equals(t.getStatus()))return t;
            if(TaskStatus.FAILED.equals(t.getStatus()))fail("设备任务失败: "+t.getError_code());
        }
        fail("任务未完成: status="+t.getStatus()+",step="+t.getHandshake_step()+",held="+t.getHeld_reason());return t;
    }
    long writes(Fixture f) { return jdbc.queryForObject("select count(*) from wcs_message_log where app_id=? and direction='write'",Long.class,f.down().getId()); }
    long rows(Fixture f) { return jdbc.queryForObject("select count(*) from wcs_task where app_id=?",Long.class,f.down().getId()); }
    JsonNode post(String path,Object body,String bearer)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
        if(bearer!=null)builder.header("Authorization","Bearer "+bearer);
        var response=HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body());
    }

    @ParameterizedTest @ValueSource(strings={"stacker","conveyor","lift","auto_door"})
    void allImplementedTemplatesRunToCompletedAndPersistDeviceResult(String type) {
        Fixture f=fixture(type);String business="LETTERS-2025-11122233344455566677788";
        TaskEntity t=complete(f,dispatch(f,business,payload(type)));
        assertThat(t.getTask_no()).isEqualTo(business);assertThat(t.getResult()).containsEntry("quality","good");
        assertThat(t.getResult().get("payload")).isInstanceOf(Map.class);assertThat(writes(f)).isGreaterThan(1);
        if(!"auto_door".equals(type)) {
            assertThat(t.getNative_task_num()).isEqualTo(1L);
            Map<?,?> result=(Map<?,?>)t.getResult().get("payload");assertThat(((Number)result.get("status_TaskNum")).longValue()).isEqualTo(1L);
        } else assertThat(t.getNative_task_num()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where app_id=?",Long.class,f.down().getId())).isZero();
    }
    @Test void malformedPayloadCreatesNoTaskAndWritesNoDevicePoints() {
        Fixture f=fixture("stacker");long beforeRows=rows(f),beforeWrites=writes(f);Map<String,Object> beforeState=device(f).snapshot();Map<String,Object> internal=new LinkedHashMap<>(payload("stacker"));internal.put("cmd_ConfirmTask",1);
        Map<String,Object> fraction=new LinkedHashMap<>(payload("stacker"));fraction.put("cmd_TakeCoor_Row",1.1);
        for(Map<String,Object> invalid:List.of(Map.<String,Object>of(),internal,fraction)) assertThatThrownBy(()->dispatch(f,"BAD-"+UUID.randomUUID(),invalid)).isInstanceOf(WcsException.class);
        assertThat(rows(f)).isEqualTo(beforeRows);assertThat(writes(f)).isEqualTo(beforeWrites);assertThat(device(f).snapshot()).isEqualTo(beforeState);
    }
    @ParameterizedTest @ValueSource(strings={"stacker","conveyor","lift","auto_door"})
    void eachDeviceRejectsItsInvalidBusinessValueBeforeAdmission(String type) {
        Fixture f=fixture(type);Map<String,Object> invalid=new LinkedHashMap<>(payload(type));
        switch(type) {case "stacker"->invalid.put("cmd_PutCoor_Row",0);case "conveyor"->invalid.put("cmd_DestStation",0);case "lift"->invalid.put("cmd_TargetFloor",0);case "auto_door"->invalid.put("cmd_Close",1);default->throw new IllegalArgumentException();}
        long before=rows(f),beforeWrites=writes(f);Map<String,Object> state=device(f).snapshot();
        assertThatThrownBy(()->dispatch(f,"INVALID-"+UUID.randomUUID(),invalid)).isInstanceOf(WcsException.class).satisfies(ex->assertThat(((WcsException)ex).getCode()).isEqualTo(4241));
        assertThat(rows(f)).isEqualTo(before);assertThat(writes(f)).isEqualTo(beforeWrites);assertThat(device(f).snapshot()).isEqualTo(state);
    }
    @Test void sameBusinessNumberRequiresSameContentAndTrustedCaller() {
        Fixture f=fixture("conveyor");TaskEntity t=dispatch(f,"DUP-"+UUID.randomUUID(),payload("conveyor"));
        assertThat(dispatch(f,t.getTask_no(),payload("conveyor")).getId()).isEqualTo(t.getId());
        assertThatThrownBy(()->dispatch(f,t.getTask_no(),Map.of("cmd_DestStation",4))).isInstanceOf(WcsException.class).satisfies(ex->assertThat(((WcsException)ex).getCode()).isEqualTo(409));
        ApplicationEntity other=app(publish(protocol("HTTP_DEVICE_CONTRACT")),"upstream","none");bindings.grant(other.getId(),List.of(f.down().getId()),"dispatch");
        assertThatThrownBy(()->tasks.dispatch(new TaskService.DispatchRequest(f.down().getId(),t.getTask_no(),0L,"TEST-2025",payload("conveyor")),other.getId())).isInstanceOf(WcsException.class).satisfies(ex->assertThat(((WcsException)ex).getCode()).isEqualTo(409));
        assertThat(rows(f)).isEqualTo(1);
    }
    @Test void revokedAuthorityBeforeStartHoldsTaskWithZeroNewDeviceWrites() {
        Fixture f=fixture("stacker");TaskEntity t=dispatch(f,"REVOKED-"+UUID.randomUUID(),payload("stacker"));long before=writes(f);
        bindings.delete(f.binding().getId());engine.advanceApp(f.down().getId(),"stacker");
        t=tasks.getById(t.getId());assertThat(t.getStatus()).isEqualTo("pending");assertThat(t.getHeld_reason()).isNotBlank();assertThat(t.getPhysical_started()).isFalse();assertThat(writes(f)).isEqualTo(before);
    }
    @Test void alreadyAcceptedPhysicalTaskFinishesAfterAuthorityRevocation() {
        Fixture f=fixture("stacker");TaskEntity t=dispatch(f,"INFLIGHT-"+UUID.randomUUID(),payload("stacker"));
        for(int i=0;i<10 && !Boolean.TRUE.equals(t.getPhysical_started());i++) {engine.advanceApp(f.down().getId(),"stacker");t=tasks.getById(t.getId());}
        assertThat(t.getPhysical_started()).isTrue();bindings.delete(f.binding().getId());
        t=complete(f,t);assertThat(t.getStatus()).isEqualTo("completed");assertThat(t.getResult()).isNotNull();assertThat(device(f).get("status_Task")).isEqualTo(0);
    }
    @Test void aLaterTaskCannotReplaceTheEarlierPersistedResult() {
        Fixture f=fixture("stacker");TaskEntity first=complete(f,dispatch(f,"FIRST-"+UUID.randomUUID(),payload("stacker")));
        Map<String,Object> original=first.getResult();TaskEntity second=complete(f,dispatch(f,"SECOND-"+UUID.randomUUID(),payload("stacker")));
        assertThat(second.getNative_task_num()).isEqualTo(2L);assertThat(tasks.getById(first.getId()).getResult()).isEqualTo(original);
        Map<?,?> old=(Map<?,?>)tasks.getById(first.getId()).getResult().get("payload");assertThat(((Number)old.get("status_TaskNum")).longValue()).isEqualTo(1L);
        assertThat(((Number)device(f).get("status_TaskNum")).longValue()).isEqualTo(2L);
    }
    @Test void cancelledUnstartedReservationIsReleasedWithoutPhysicalWrite() {
        Fixture f=fixture("conveyor");TaskEntity t=dispatch(f,"CANCEL-"+UUID.randomUUID(),payload("conveyor"));long before=writes(f);
        assertThat(execution.prepare(t)).isTrue();assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,t.getId())).isEqualTo(1L);
        assertThat(tasks.cancel(t.getId())).isTrue();assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,t.getId())).isZero();
        assertThat(writes(f)).isEqualTo(before);
    }
    @Test void activeTaskPreventsApplicationVersionSwitchAndOldRetryIsRejected() {
        Fixture f=fixture("conveyor");TaskEntity t=dispatch(f,"VERSION-"+UUID.randomUUID(),payload("conveyor"));
        ProtocolEntity newer=publish(protocols.cloneDraft(f.profile().getId()));ApplicationEntity next=apps.getById(f.down().getId());next.setProtocol_id(newer.getId());next.setProtocol_revision_id(newer.getPublished_revision_id());
        assertThatThrownBy(()->apps.update(next)).isInstanceOf(WcsException.class).hasMessageContaining("活动任务");
        jdbc.update("update wcs_task set status='failed',handshake_step='FAILED' where id=?",t.getId());
        apps.update(next);
        assertThatThrownBy(()->tasks.retryTask(t.getId())).isInstanceOf(WcsException.class).satisfies(ex->assertThat(((WcsException)ex).getCode()).isEqualTo(409));
    }
    @Test void actualHttpRejectsLegacyFlatFieldsEvenWhenPayloadIsValid()throws Exception {
        Fixture f=fixture("stacker");
        JsonNode issued=post("/system/token",Map.of("app_id",f.up().getId().toString(),"app_key",f.up().getApp_key(),"app_secret",f.up().getApp_secret(),"grant_type","client_credentials"),null);
        assertThat(issued.path("errcode").asInt()).isZero();String token=issued.path("data").path("access_token").asText();
        long beforeRows=rows(f),beforeWrites=writes(f);Map<String,Object> state=device(f).snapshot();
        for(String field:List.of("taskType","takeRow","putColumn","portNum")) {
            Map<String,Object> data=new LinkedHashMap<>(Map.of("appId",f.down().getId(),"taskNo","HTTP-BAD-"+UUID.randomUUID(),"payload",payload("stacker")));data.put(field,1);
            JsonNode response=post("/open/task/dispatch",Map.of("app_id",f.up().getId().toString(),"app_key",f.up().getApp_key(),"access_token",token,"request_id","legacy-http","data",data),null);
            assertThat(response.path("errcode").asInt()).isEqualTo(4241);
        }
        Map<String,Object> segment=new LinkedHashMap<>(Map.of("seq",1,"appId",f.down().getId(),"payload",payload("stacker")));segment.put("taskType",3);
        String chainNo="HTTP-CHAIN-BAD-"+UUID.randomUUID();
        JsonNode chain=post("/open/chain/dispatch",Map.of("app_id",f.up().getId().toString(),"app_key",f.up().getApp_key(),"access_token",token,"request_id","legacy-chain-http","data",Map.of("chainNo",chainNo,"segments",List.of(segment))),null);
        assertThat(chain.path("errcode").asInt()).isEqualTo(4241);assertThat(jdbc.queryForObject("select count(*) from wcs_task_chain where chain_no=?",Long.class,chainNo)).isZero();
        String admin=jwt.generateToken(new JwtUser(1L,"admin","admin","admin",1L,1L),10);
        JsonNode management=post("/task/create",Map.of("app_id",f.down().getId(),"task_no","ADMIN-LEGACY-"+UUID.randomUUID(),"take_row",1,"payload",payload("stacker")),admin);
        assertThat(management.path("code").asInt()).isEqualTo(4241);
        assertThat(rows(f)).isEqualTo(beforeRows);assertThat(writes(f)).isEqualTo(beforeWrites);assertThat(device(f).snapshot()).isEqualTo(state);
        String validNo="HTTP-VALID-"+UUID.randomUUID();
        JsonNode accepted=post("/open/task/dispatch",Map.of("app_id",f.up().getId().toString(),"app_key",f.up().getApp_key(),"access_token",token,"request_id","valid-http","data",Map.of("appId",f.down().getId(),"taskNo",validNo,"payload",payload("stacker"))),null);
        assertThat(accepted.path("errcode").asInt()).isZero();assertThat(accepted.path("data").path("payload").path("cmd_TaskType").asInt()).isEqualTo(3);assertThat(accepted.path("data").has("take_row")).isFalse();
        TaskEntity completed=complete(f,tasks.getById(accepted.path("data").path("id").asLong()));assertThat(completed.getTask_no()).isEqualTo(validNo);
    }
    TaskEntity atClearing(Fixture f,TaskEntity task) {
        for(int i=0;i<16;i++) {device(f).tick();engine.advanceApp(f.down().getId(),f.down().getDevice_type());task=tasks.getById(task.getId());if("CLEARING".equals(task.getHandshake_step()))return task;}
        fail("设备未进入持久化清理阶段");return task;
    }
    @ParameterizedTest @ValueSource(strings={"conveyor","lift"})
    void cleanupDoesNotReleaseLeaseUntilRealIdleFeedback(String type) {
        DeviceSim delayed="conveyor".equals(type)?new DelayedConveyor():new DelayedLift();Fixture f=fixture(type,delayed);
        TaskEntity task=atClearing(f,dispatch(f,"CLEAR-WAIT-"+UUID.randomUUID(),payload(type)));
        assertThat(task.getResult()).isNotNull();assertThat(task.getStatus()).isEqualTo("executing");
        Map<String,Object> result=task.getResult();engine.advanceApp(f.down().getId(),type);task=tasks.getById(task.getId());
        assertThat(task.getStatus()).isEqualTo("executing");assertThat(task.getHandshake_step()).isEqualTo("CLEARING");assertThat(((Number)delayed.get("status_Task")).intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,task.getId())).isEqualTo(1L);
        if(delayed instanceof DelayedConveyor conveyor)conveyor.hold=false;else((DelayedLift)delayed).hold=false;
        task=complete(f,task);assertThat(task.getResult()).isEqualTo(result);assertThat(((Number)delayed.get("status_Task")).intValue()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,task.getId())).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"conveyor","lift"})
    void savedResultResumesAfterClearReachedDeviceButProcessDidNotFinish(String type) {
        Fixture f=fixture(type);TaskEntity task=atClearing(f,dispatch(f,"CLEAR-RESTART-"+UUID.randomUUID(),payload(type)));Map<String,Object> result=task.getResult();
        assertThat(result).isNotNull();assertThat(((Number)device(f).get("status_Task")).intValue()).isEqualTo(2);
        device(f).set("conveyor".equals(type)?"cmd_Start":"cmd_Go",0);
        assertThat(((Number)device(f).get("status_Task")).intValue()).isZero();
        // 恢复在结果已提交、阶段提交/最终保存未完成的边界；下一步只读取持久化记录。
        jdbc.update("update wcs_task set handshake_step='MOVING' where id=?",task.getId());
        task=complete(f,tasks.getById(task.getId()));assertThat(task.getStatus()).isEqualTo("completed");assertThat(task.getResult()).isEqualTo(result);
        Map<?,?> captured=(Map<?,?>)task.getResult().get("payload");assertThat(((Number)captured.get("status_Task")).intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,task.getId())).isZero();
    }
    static final class DelayedConveyor extends com.cayleywcs.simulator.ConveyorDeviceState {
        boolean hold=true;
        @Override protected void onSet(String field,int value) {if(hold&&field.equals("cmd_Start")&&value==0&&intVal("status_Task")==2)return;super.onSet(field,value);}
    }
    static final class DelayedLift extends com.cayleywcs.simulator.LiftDeviceState {
        boolean hold=true;
        @Override protected void onSet(String field,int value) {if(hold&&field.equals("cmd_Go")&&value==0&&intVal("status_Task")==2)return;super.onSet(field,value);}
    }
    @Test void freshMaintenanceModeAllowsOnePointAndRejectsWholeInvalidBatchBeforeAnyWrite() {
        Fixture f=fixture("conveyor");
        apps.updateWorkMode(f.down().getId(),"maintenance");
        var receipt=connections.writePayload(f.down().getId(),Map.of("cmd_DestStation",4));
        assertThat(receipt).containsEntry("quality","good");assertThat(((Number)device(f).get("cmd_DestStation")).intValue()).isEqualTo(4);
        long before=writes(f);Map<String,Object> state=device(f).snapshot();
        assertThatThrownBy(()->connections.writePayload(f.down().getId(),Map.of("cmd_DestStation",32768))).isInstanceOf(WcsException.class);
        assertThatThrownBy(()->connections.writePayload(f.down().getId(),Map.of("cmd_DestStation",5,"cmd_TaskNum",1))).isInstanceOf(WcsException.class);
        assertThatThrownBy(()->connections.writePayload(f.down().getId(),Map.of("status_Task",1)))
                .isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(403));
        assertThat(writes(f)).isEqualTo(before);assertThat(device(f).snapshot()).isEqualTo(state);
        dispatch(f,"MAINT-PENDING-"+UUID.randomUUID(),payload("conveyor"));
        assertThatThrownBy(()->connections.writePayload(f.down().getId(),Map.of("cmd_DestStation",5))).isInstanceOf(WcsException.class).hasMessageContaining("任务");
        assertThat(writes(f)).isEqualTo(before);assertThat(device(f).snapshot()).isEqualTo(state);
    }
    @Test void managementReadsOnlyBusinessResponseWhileDriverReadsBackInternalRequest() {
        Fixture f=fixture("stacker");
        var read=connections.readPayload(f.down().getId(),java.util.Collections.singletonMap("status_Mode",null));
        assertThat(((Number)((Map<?,?>)read.get("payload")).get("status_Mode")).longValue()).isEqualTo(2L);
        for(String field:List.of("cmd_TaskType","cmd_TaskNum","PLC_Heart")) {
            assertThatThrownBy(()->connections.readPayload(f.down().getId(),java.util.Collections.singletonMap(field,null)))
                    .isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(403));
        }
        connections.writePoint(f.down().getId(),"cmd_TaskNum",123);
        assertThat(connections.readPoint(f.down().getId(),"cmd_TaskNum")).isEqualTo(123L);
        var result=connections.responsePayload(f.down().getId());
        assertThat(result).containsKey("status_Task").doesNotContainKeys("cmd_TaskNum","WCS_Heart","PLC_Heart");
    }
    @Test void failedPhysicalTaskKeepsItsLeaseUntilConfirmedFaultResetThenRetriesWithNewNativeNumber() {
        Fixture f=fixture("stacker");TaskEntity task=dispatch(f,"FAULT-RETRY-"+UUID.randomUUID(),payload("stacker"));
        for(int i=0;i<12 && ((Number)device(f).get("status_Task")).intValue()!=1;i++) {
            engine.advanceApp(f.down().getId(),"stacker");task=tasks.getById(task.getId());
        }
        assertThat(((Number)device(f).get("status_Task")).intValue()).isEqualTo(1);
        assertThat(task.getPhysical_started()).isTrue();Long oldNative=task.getNative_task_num();
        long before=writes(f);assertThatThrownBy(()->connections.resetFault(f.down().getId())).isInstanceOf(WcsException.class).hasMessageContaining("执行");
        assertThat(writes(f)).isEqualTo(before);
        ((com.cayleywcs.simulator.StackerDeviceState)device(f)).injectFault(55);
        engine.advanceApp(f.down().getId(),"stacker");task=tasks.getById(task.getId());
        assertThat(task.getStatus()).isEqualTo("failed");assertThat(task.getError_code()).isEqualTo(55L);assertThat(task.getPhysical_started()).isTrue();
        assertThat(((Number)device(f).get("status_Task")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select native_number from wcs_device_task_number_lease where task_id=?",Long.class,task.getId())).isEqualTo(oldNative);
        connections.resetFault(f.down().getId());task=tasks.getById(task.getId());
        assertThat(((Number)device(f).get("status_Task")).intValue()).isZero();
        assertThat((List<?>)device(f).get("status_ErrorCode")).allSatisfy(value->assertThat(((Number)value).intValue()).isZero());
        assertThat(task.getStatus()).isEqualTo("failed");assertThat(task.getPhysical_started()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,task.getId())).isZero();
        assertThat(tasks.retryTask(task.getId())).isTrue();task=complete(f,tasks.getById(task.getId()));
        assertThat(task.getStatus()).isEqualTo("completed");assertThat(task.getRetry_count()).isEqualTo(1L);assertThat(task.getNative_task_num()).isEqualTo(oldNative+1);
        Map<?,?> feedback=(Map<?,?>)task.getResult().get("payload");assertThat(((Number)feedback.get("status_TaskNum")).longValue()).isEqualTo(task.getNative_task_num());
        assertThat(jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,task.getId())).isZero();
    }
}
