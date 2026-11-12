package com.cayleywcs.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.security.AppKeyReplayCache;
import com.cayleywcs.audit.AuditService;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.ResourceLifecycleGuard;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.faultcode.FaultCodeResolver;
import com.cayleywcs.faultcode.FaultCodeService;
import com.cayleywcs.faultcode.entity.FaultCodeEntity;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.telemetry.SensorApplicationService;
import com.cayleywcs.telemetry.TelemetryService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real commits and rollbacks: deleting a parent must neither strand children nor evict on rollback. */
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:resource_deletion;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=10000",
        "cayleywcs.task.engine-interval-ms=3600000", "cayleywcs.chain.orchestrate-interval-ms=3600000"})
@ActiveProfiles("test-flyway")
class ResourceDeletionTest {
    @Autowired ApplicationService apps;
    @Autowired ProtocolService protocols;
    @Autowired FaultCodeResolver faults;
    @Autowired FaultCodeService faultCodes;
    @Autowired ConnectionManager connections;
    @Autowired TelemetryService telemetry;
    @Autowired SensorApplicationService sensors;
    @Autowired AlarmService alarms;
    @Autowired AuditService audit;
    @Autowired ResourceLifecycleGuard guard;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AppKeyReplayCache auth;
    @MockitoBean StringRedisTemplate redis;

    @BeforeEach void login(){
        user(1L);
        when(redis.scan(any())).thenAnswer(call->mock(Cursor.class));
    }
    @AfterEach void logout(){ SecurityContextHolder.clearContext(); }
    static void user(Long tenant) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtUser(1L,"admin","admin","admin",tenant,1L),null,List.of()));
    }
    static String unique(){return UUID.randomUUID().toString();}
    ApplicationEntity app(){return app("conveyor");}
    ApplicationEntity app(String type){
        var app=new ApplicationEntity();app.setApp_code("DELETE_"+unique());app.setApp_name("删除测试");
        app.setDevice_type(type);app.setDirection("downstream");app.setConn_params(Map.of());
        return apps.create(app);
    }
    ProtocolEntity published(ApplicationEntity app){
        var source=protocols.listAll().stream().filter(p->"CONVEYOR_SIM".equals(p.getProtocol_code())).findFirst().orElseThrow();
        var p=protocols.cloneDraft(source.getId(),app.getId());
        return protocols.publish(p.getId(),p.getRow_version());
    }
    void select(ApplicationEntity app,ProtocolEntity p){
        app.setProtocol_id(p.getId());app.setProtocol_revision_id(p.getPublished_revision_id());apps.update(app);
    }
    long count(String table,String column,Long id){return jdbc.queryForObject("select count(*) from "+table+" where "+column+"=?",Long.class,id);}
    void conflict(Runnable work,String message){
        assertThatThrownBy(work::run).isInstanceOfSatisfying(WcsException.class,ex->assertThat(ex.getCode()).isEqualTo(409)).hasMessageContaining(message);
    }
    FaultCodeEntity fault(ProtocolEntity p){
        var code=new FaultCodeEntity();code.setProtocol_id(p.getId());code.setCode(981L);code.setName("测试故障");
        code.setLevel("error");code.setMessage("");code.setSuggestion("");return faultCodes.create(code);
    }
    void audit(String type,Long id){jdbc.update("insert into wcs_contract_migration_audit(entity_type,entity_id,action,details,tenant_id) values(?,?,'test','{}',1)",type,id);}
    void absentProtocol(ProtocolEntity p){
        assertThat(count("wcs_protocol","id",p.getId())).isZero();
        for(String table:List.of("wcs_protocol_point","wcs_protocol_revision","wcs_fault_code"))
            assertThat(count(table,"protocol_id",p.getId())).as(table).isZero();
    }

    @Test void deletingUnselectedPublicationRemovesAllChildrenAndOnlyItsCache(){
        var app=app();var target=published(app);var keep=published(app());
        fault(target);fault(keep);
        assertThat(faults.resolve(target.getId(),981).known()).isTrue();
        assertThat(faults.resolve(keep.getId(),981).known()).isTrue();
        audit("protocol",target.getId());audit("revision",target.getPublished_revision_id());
        var point=protocols.listPoints(target.getId()).getFirst();audit("point",point.getId());
        jdbc.update("update wcs_protocol_point set is_valid=false where id=?",point.getId());
        assertThat(protocols.delete(target.getId())).isTrue();absentProtocol(target);
        assertThat(faults.resolve(target.getId(),981).known()).isFalse();
        assertThat(faults.resolve(keep.getId(),981).known()).isTrue();
        assertThat(apps.getById(app.getId())).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from wcs_contract_migration_audit where (entity_type='protocol' and entity_id=?) or (entity_type='revision' and entity_id=?) or (entity_type='point' and entity_id=?)",Long.class,target.getId(),target.getPublished_revision_id(),point.getId())).isZero();
        var code=new FaultCodeEntity();code.setProtocol_id(target.getId());code.setCode(1L);
        assertThatThrownBy(()->faultCodes.create(code)).isInstanceOf(WcsException.class);
    }

    @Test void deletingApplicationClearsSelectionsPublicationsTokensLogsAndLateEvents(){
        var app=app();var selected=published(app);var retired=published(app);select(app,selected);fault(selected);
        jdbc.update("update wcs_protocol set is_valid=false,lifecycle_state='retired' where id=?",retired.getId());
        jdbc.update("insert into wcs_dashboard_point_selection(tenant_id,user_id,app_id,fields) values(1,1,?,'[]')",app.getId());
        jdbc.update("insert into wcs_open_token(token_hash,app_id,tenant_id,credential_hash,issued_at,expires_at) values(?,?,1,'test',0,9999999999)",unique(),app.getId());
        jdbc.update("insert into wcs_device_task_number(app_id) values(?)",app.getId());audit("application",app.getId());
        alarms.raise(app.getId(),981,"error","test","");
        audit.writeConnectionLog(app.getId(),app.getApp_code(),"test","CLOSED","");
        audit.writeMessageLog(app.getId(),"read","status","","0","{}");
        assertThat(apps.delete(app.getId())).isTrue();absentProtocol(selected);absentProtocol(retired);
        verify(auth).evictApplication(app.getApp_key());
        assertThat(count("wcs_application","id",app.getId())).isZero();
        alarms.raise(app.getId(),981,"error","late","");
        audit.writeConnectionLog(app.getId(),app.getApp_code(),"late","CLOSED","");
        audit.writeMessageLog(app.getId(),"read","late","","0","{}");
        for(String table:List.of("wcs_open_token","wcs_dashboard_point_selection","wcs_device_task_number","wcs_alarm","wcs_connection_log","wcs_message_log"))
            assertThat(count(table,"app_id",app.getId())).as(table).isZero();
    }

    @Test void rollbackPreservesChildrenAndDoesNotEvictCaches(){
        var app=app();var p=published(app);select(app,p);fault(p);
        var cached=faults.resolve(p.getId(),981);
        new TransactionTemplate(transactions).execute(status->{
            assertThat(apps.delete(app.getId())).isTrue();status.setRollbackOnly();return null;
        });
        assertThat(apps.getById(app.getId()).getProtocol_id()).isEqualTo(p.getId());
        assertThat(count("wcs_protocol_revision","protocol_id",p.getId())).isPositive();
        assertThat(faults.resolve(p.getId(),981)).isSameAs(cached);
        verify(auth,never()).evictApplication(app.getApp_key());
    }

    @ParameterizedTest @ValueSource(strings={"app_id","caller_app_id"})
    void historicalTaskReferencePreventsApplicationDeletion(String reference){
        var app=app();var p=published(app);String no=unique();
        jdbc.update("insert into wcs_task(task_no,app_id,caller_app_id,status,is_valid) values(?,?,?,'completed',false)",no,reference.equals("app_id")?app.getId():1L,reference.equals("caller_app_id")?app.getId():0L);
        try{conflict(()->apps.delete(app.getId()),"任务");assertThat(count("wcs_protocol_point","protocol_id",p.getId())).isPositive();}
        finally{jdbc.update("delete from wcs_task where task_no=?",no);}
    }

    @Test void historicalTaskPinsUnselectedProtocolVersion(){
        var p=published(app());String no=unique();
        jdbc.update("insert into wcs_task(task_no,app_id,status,protocol_revision_id) values(?,1,'completed',?)",no,p.getPublished_revision_id());
        try{conflict(()->protocols.delete(p.getId()),"任务");}
        finally{jdbc.update("delete from wcs_task where task_no=?",no);}
    }

    @ParameterizedTest @ValueSource(strings={"upstream","downstream"})
    void revokedAuthorizationStillProtectsApplicationAndPinnedVersion(String side){
        var app=app();var p=published(app);
        Long up=side.equals("upstream")?app.getId():1L,down=side.equals("downstream")?app.getId():1L;
        jdbc.update("insert into wcs_app_binding(upstream_app_id,downstream_app_id,enabled,is_valid,"+side+"_revision_id) values(?,?,false,false,?)",up,down,p.getPublished_revision_id());
        try{conflict(()->apps.delete(app.getId()),"授权");conflict(()->protocols.delete(p.getId()),"授权");}
        finally{jdbc.update("delete from wcs_app_binding where upstream_app_id=? and downstream_app_id=?",up,down);}
    }

    @ParameterizedTest @ValueSource(strings={"appId","candidateAppIds","pinnedRevisionIds","original_segments","upstream"})
    void completedChainsProtectCandidatesOriginalSegmentsAndVersions(String kind){
        var app=app();var p=published(app);String no=unique();
        String segment=switch(kind){
            case "appId","original_segments" -> "[{\"appId\":"+app.getId()+"}]";
            case "candidateAppIds" -> "[{\"candidateAppIds\":["+app.getId()+"]}]";
            case "pinnedRevisionIds" -> "[{\"pinnedRevisionIds\":{\""+app.getId()+"\":"+p.getPublished_revision_id()+"}}]";
            default -> "[]";
        };
        jdbc.update("insert into wcs_task_chain(chain_no,upstream_app_id,status,segments,original_segments,is_valid) values(?,?,'completed',?,?,false)",no,kind.equals("upstream")?app.getId():0L,kind.equals("original_segments")?"[]":segment,kind.equals("original_segments")?segment:null);
        try{
            conflict(()->apps.delete(app.getId()),"任务链");
            if(kind.equals("pinnedRevisionIds"))conflict(()->protocols.delete(p.getId()),"任务链");
        }finally{jdbc.update("delete from wcs_task_chain where chain_no=?",no);}
    }

    @Test void selectedProtocolAndOpenApplicationAreProtected(){
        var app=app();var p=published(app);select(app,p);
        conflict(()->protocols.delete(p.getId()),"选用");
        jdbc.update("update wcs_application set desired_state='open' where id=?",app.getId());
        try{conflict(()->apps.delete(app.getId()),"关闭");}
        finally{jdbc.update("update wcs_application set desired_state='closed' where id=?",app.getId());}
    }

    @Test void connectedApplicationMustBeClosedBeforeDeletion(){
        var app=app();var p=published(app);select(app,p);
        connections.open(app.getId());
        try{conflict(()->apps.delete(app.getId()),"关闭");assertThat(connections.cachedSnapshot(app.getId())).isNotNull();}
        finally{connections.close(app.getId());}
        assertThat(apps.delete(app.getId())).isTrue();
        assertThat(connections.cachedSnapshot(app.getId())).isNull();
    }

    @Test void deviceNumberLeaseAndMalformedChainFailClosed(){
        var app=app();String no=unique();
        jdbc.update("insert into wcs_device_task_number_lease(app_id,native_number,task_id) values(?,1,?)",app.getId(),app.getId()+900000);
        try{conflict(()->apps.delete(app.getId()),"占用");}
        finally{jdbc.update("delete from wcs_device_task_number_lease where app_id=?",app.getId());}
        jdbc.update("insert into wcs_task_chain(chain_no,status,segments) values(?,'completed','broken')",no);
        try{conflict(()->apps.delete(app.getId()),"格式异常");}
        finally{jdbc.update("delete from wcs_task_chain where chain_no=?",no);}
        assertThat(apps.getById(app.getId())).isNotNull();
    }

    @Test void callbackScopeAndPendingDeliveryBlockWhileCompletedDeliveryIsCleaned(){
        var app=app();String name=unique();
        jdbc.update("insert into wcs_webhook(name,url,scope_app_ids,enabled) values(?,'http://localhost/unused',?,false)",name,"["+app.getId()+"]");
        Long hook=jdbc.queryForObject("select id from wcs_webhook where name=?",Long.class,name);
        try{
            conflict(()->apps.delete(app.getId()),"Webhook");
            jdbc.update("update wcs_webhook set scope_app_ids='[]' where id=?",hook);
            jdbc.update("insert into wcs_webhook_delivery(webhook_id,event_type,biz_id,payload,status) values(?,'alarm.raised',1,?,'pending')",hook,"{\"appId\":"+app.getId()+"}");
            conflict(()->apps.delete(app.getId()),"待投递");
            jdbc.update("update wcs_webhook_delivery set status='ok' where webhook_id=?",hook);
            assertThat(apps.delete(app.getId())).isTrue();
            assertThat(count("wcs_webhook_delivery","webhook_id",hook)).isZero();
            assertThat(count("wcs_webhook","id",hook)).isOne();
        }finally{jdbc.update("delete from wcs_webhook_delivery where webhook_id=?",hook);jdbc.update("delete from wcs_webhook where id=?",hook);}
    }

    @Test void anotherTenantsDeletionCannotReadOrChangeTheResource(){
        var app=app();var p=published(app);user(2L);
        assertThatThrownBy(()->apps.delete(app.getId())).isInstanceOfSatisfying(WcsException.class,e->assertThat(e.getCode()).isEqualTo(404));
        assertThatThrownBy(()->protocols.delete(p.getId())).isInstanceOfSatisfying(WcsException.class,e->assertThat(e.getCode()).isEqualTo(404));
        assertThat(count("wcs_protocol","id",p.getId())).isOne();
    }

    @Test void sensorDeletionPreservesSiblingApplicationsAndSharedGateway(){
        var parent=app("agv");var gateway=telemetry.configure(new TelemetryService.Configure(null,parent.getId(),"test-"+unique(),0,0d,600d,null));
        var links=sensors.provision(gateway.getId());var target=apps.getById(links.getFirst().appId());
        jdbc.update("insert into wcs_telemetry_cycle(gateway_id,cycle_id,config_version,started_at,finished_at,duration_ms,connection_state,measurements,diagnostics,tenant_id) values(?,?,1,0,1,1,'STOPPED','{}','{}',1)",gateway.getId(),unique());
        jdbc.update("insert into wcs_telemetry_latest(gateway_id,metric,observed_at,config_version,sample,tenant_id) values(?,'test',1,1,'{}',1)",gateway.getId());
        conflict(()->apps.delete(parent.getId()),"传感器");
        assertThat(apps.delete(target.getId())).isTrue();
        assertThat(count("wcs_sensor_application","gateway_id",gateway.getId())).isEqualTo(2);
        assertThat(count("wcs_sensor_gateway","id",gateway.getId())).isOne();
        assertThat(count("wcs_telemetry_cycle","gateway_id",gateway.getId())).isOne();
        for(var link:links.subList(1,3))assertThat(apps.getById(link.appId())).isNotNull();
        for(var link:links.subList(1,3))apps.delete(link.appId());
        assertThat(apps.delete(parent.getId())).isTrue();
        assertThat(count("wcs_sensor_gateway","id",gateway.getId())).isZero();
        assertThat(count("wcs_telemetry_cycle","gateway_id",gateway.getId())).isZero();
        assertThat(count("wcs_telemetry_latest","gateway_id",gateway.getId())).isZero();
    }

    @Test void concurrentReferenceCommitsBeforeDeleteAndIsThenProtected() throws Exception {
        var app=app();String no=unique();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var writer=pool.submit(()->guard.referenceWrite(()->{
                jdbc.update("insert into wcs_task(task_no,app_id,status) values(?,?,'completed')",no,app.getId());
                locked.countDown();await(release);return null;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var deletion=pool.submit(()->{user(1L);try{apps.delete(app.getId());return 0;}catch(WcsException e){return e.getCode();}finally{SecurityContextHolder.clearContext();}});
            try{assertThatThrownBy(()->deletion.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally{release.countDown();}
            writer.get(5,TimeUnit.SECONDS);assertThat(deletion.get(5,TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(count("wcs_application","id",app.getId())).isOne();
        }finally{release.countDown();jdbc.update("delete from wcs_task where task_no=?",no);}
    }

    @Test void referenceWriterWaitsForDeleteCommitAndThenSeesMissingApplication() throws Exception {
        var app=app();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var deletion=pool.submit(()->{user(1L);try{return new TransactionTemplate(transactions).execute(s->{apps.delete(app.getId());locked.countDown();await(release);return true;});}finally{SecurityContextHolder.clearContext();}});
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->{user(1L);try{return guard.referenceWrite(()->{apps.getById(app.getId());return 0;});}catch(WcsException e){return e.getCode();}finally{SecurityContextHolder.clearContext();}});
            try{assertThatThrownBy(()->writer.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally{release.countDown();}
            assertThat(deletion.get(5,TimeUnit.SECONDS)).isTrue();assertThat(writer.get(5,TimeUnit.SECONDS)).isEqualTo(404);
        }finally{release.countDown();}
    }
    static void await(CountDownLatch latch){try{if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("latch timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
