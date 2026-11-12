package com.cayleywcs.protocol;

import static org.assertj.core.api.Assertions.*;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:protocol_contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles("test-flyway")
@Transactional
class ProtocolContractPublicationTest {
    @Autowired ProtocolService service;
    @Autowired ProtocolContractService contracts;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.cayleywcs.application.ApplicationService applications;
    @Autowired com.cayleywcs.connection.ConnectionManager connections;
    @BeforeEach void user() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(1L,"admin","admin","admin",1L,1L),null,List.of())); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    ProtocolEntity protocol(String code) { return service.listAll().stream().filter(p -> code.equals(p.getProtocol_code())).findFirst().orElseThrow(); }

    @Test void explicitMultiTemplateHttpSeedPublishesWithImmutableRevision() {
        ProtocolEntity p = protocol("HTTP_DEVICE_CONTRACT");
        assertThat(service.listPoints(p.getId()).stream().map(ProtocolPointEntity::getTemplate_code).distinct()).containsExactlyInAnyOrder("stacker","conveyor","lift","auto_door");
        p = service.publish(p.getId(),p.getRow_version());
        assertThat(p.getLifecycle_state()).isEqualTo("published"); assertThat(p.getPublished_revision_id()).isNotNull();
        var revision = contracts.resolveRevision(p.getPublished_revision_id(),"auto_door");
        assertThat(revision.points()).hasSize(5);
        assertThat(contracts.validatePayload(revision,Map.of("cmd_Open",1,"cmd_Close",0))).containsKey("cmd_Open");
        ProtocolEntity frozen = p;
        assertThatThrownBy(() -> service.update(frozen)).hasMessageContaining("草稿");
        ProtocolPointEntity point = service.listPoints(p.getId()).getFirst();
        assertThatThrownBy(() -> service.updatePoint(point)).hasMessageContaining("草稿");
        assertThatThrownBy(() -> service.deletePoint(point.getId(),point.getRow_version())).hasMessageContaining("草稿");
        ProtocolEntity draft = service.cloneDraft(p.getId());
        assertThat(draft.getLifecycle_state()).isEqualTo("draft"); assertThat(draft.getPublished_revision_id()).isNull();
        assertThat(service.listPoints(draft.getId())).hasSize(service.listPoints(p.getId()).size());
        assertThat(contracts.resolveRevision(p.getPublished_revision_id(),"auto_door").fingerprint()).isEqualTo(revision.fingerprint());
    }
    @Test void physicalTaskNumberContradictionStillCannotPublishWithoutAccessFlags() {
        ProtocolEntity physical = protocol("STACKER_STD");
        jdbc.update("update wcs_protocol_point set address='ns=2;s='||field_name where protocol_id=?",physical.getId());
        assertThatThrownBy(() -> service.publish(physical.getId(),physical.getRow_version())).hasMessageContaining("cmd_TaskNum");
        ProtocolEntity sim = protocol("PALLET_SIM_OPCUA");
        assertThat(service.publish(sim.getId(),sim.getRow_version()).getPublished_revision_id()).isNotNull();
    }
    @Test void pointsCanBeCreatedUpdatedAndPublishedWithoutRetiredPointMetadata() throws Exception {
        ProtocolEntity source = protocol("DOOR_SIM");
        ProtocolEntity draft = new ProtocolEntity(); draft.setProtocol_code("NO_ACCESS_FLAG"); draft.setProtocol_name("按方向与驱动定义"); draft.setProtocol_type("sim"); draft.setApp_id(source.getApp_id()); draft = service.create(draft);
        for (ProtocolPointEntity point : service.listPoints(source.getId())) {
            point.setId(null); point.setProtocol_id(draft.getId()); service.createPoint(point);
        }
        ProtocolPointEntity point = service.listPoints(draft.getId()).getFirst(); point.setDescription("没有独立读写权限配置"); service.updatePoint(point);
        ProtocolEntity current = service.getById(draft.getId()); ProtocolEntity published = service.publish(current.getId(),current.getRow_version());
        var revision = contracts.resolveRevision(published.getPublished_revision_id(),"auto_door");
        assertThat(revision.points()).hasSize(service.listPoints(source.getId()).size());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        for (var row : revision.points()) {
            assertThat(json.valueToTree(row).has("rw")).isFalse();
            assertThat(json.valueToTree(row).has("point_group")).isFalse();
        }
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name='wcs_protocol_point' and column_name='rw'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name='wcs_protocol_point' and column_name='point_group'",Long.class)).isZero();
    }
    @ParameterizedTest
    @CsvSource({"WCS_Heart,response,internal", "PLC_Heart,request,internal", "cmd_TaskNum,request,business", "cmd_ConfirmTask,request,business"})
    void publicationStillRequiresDriverPointDirectionAndPurpose(String field,String direction,String purpose) {
        ProtocolEntity draft = service.cloneDraft(protocol("PALLET_SIM_OPCUA").getId());
        ProtocolPointEntity point = service.listPoints(draft.getId()).stream().filter(p -> field.equals(p.getField_name())).findFirst().orElseThrow();
        point.setDirection(direction); point.setPoint_scope(purpose);
        if ("business".equals(purpose)) point.setOperation("request".equals(direction) ? "dispatch" : "result");
        service.updatePoint(point);
        ProtocolEntity current = service.getById(draft.getId());
        assertThatThrownBy(() -> service.publish(current.getId(),current.getRow_version())).isInstanceOf(com.cayleywcs.common.exception.WcsException.class).hasMessageContaining(field)
                .satisfies(ex -> assertThat(((com.cayleywcs.common.exception.WcsException)ex).getCode()).isEqualTo(4223));
        assertThat(service.revisions(draft.getId())).isEmpty();
    }
    @Test void publicationStillRejectsMissingDriverPointAndWrongBusinessDirection() {
        ProtocolEntity missing = service.cloneDraft(protocol("PALLET_SIM_OPCUA").getId());
        ProtocolPointEntity heart = service.listPoints(missing.getId()).stream().filter(p -> "PLC_Heart".equals(p.getField_name())).findFirst().orElseThrow();
        service.deletePoint(heart.getId(),heart.getRow_version()); ProtocolEntity incomplete = service.getById(missing.getId());
        assertThatThrownBy(() -> service.publish(incomplete.getId(),incomplete.getRow_version())).hasMessageContaining("PLC_Heart");
        ProtocolEntity wrong = service.cloneDraft(protocol("HTTP_DEVICE_CONTRACT").getId());
        ProtocolPointEntity request = service.listPoints(wrong.getId()).stream().filter(p -> "cmd_TaskType".equals(p.getField_name())).findFirst().orElseThrow();
        request.setDirection("response"); request.setOperation("result"); service.updatePoint(request);
        ProtocolEntity malformed = service.getById(wrong.getId());
        assertThatThrownBy(() -> service.publish(malformed.getId(),malformed.getRow_version())).hasMessageContaining("cmd_TaskType");
    }
    @Test void pointsUseOptimisticVersionsAndStableScopedIdentity() {
        ProtocolEntity p = protocol("DOOR_SIM"); ProtocolPointEntity point = service.listPoints(p.getId()).getFirst();
        Long oldVersion = point.getRow_version(); point.setDescription("updated"); service.updatePoint(point);
        point.setRow_version(oldVersion);
        assertThatThrownBy(() -> service.updatePoint(point)).hasMessageContaining("row_version");
        assertThatThrownBy(() -> service.deletePoint(point.getId(),oldVersion)).hasMessageContaining("row_version");
        ProtocolPointEntity duplicate = service.listPoints(p.getId()).getFirst(); duplicate.setId(null);
        assertThatThrownBy(() -> service.createPoint(duplicate)).hasMessageContaining("重复");
        assertThat(service.getById(p.getId()).getRow_version()).isGreaterThan(p.getRow_version());
        assertThatThrownBy(() -> service.publish(p.getId(),p.getRow_version())).hasMessageContaining("版本");
    }
    @Test void otherFixedOwnerCannotReadOrModifyProtocol() {
        Long id = protocol("DOOR_SIM").getId();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(2L,"other","other","admin",2L,1L),null,List.of()));
        assertThat(service.listAll()).isEmpty();
        assertThatThrownBy(() -> service.getById(id)).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.listPoints(id)).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.cloneDraft(id)).hasMessageContaining("不存在");
    }
    @Test void backgroundResolutionUsesActualOwnerAndRejectsForeignProtocolOrRevision() {
        ProtocolEntity original=protocol("HTTP_DEVICE_CONTRACT"); List<ProtocolPointEntity> points=service.listPoints(original.getId());
        original=service.publish(original.getId(),original.getRow_version());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(2L,"other","other","admin",2L,1L),null,List.of()));
        var owner=new com.cayleywcs.application.entity.ApplicationEntity();owner.setApp_code("OWNER_2");owner.setApp_name("第二归属应用");owner.setDevice_type("auto_door");owner.setDirection("downstream");owner=applications.create(owner);
        ProtocolEntity second=new ProtocolEntity();second.setApp_id(owner.getId());second.setProtocol_code("OWNER2_HTTP");second.setProtocol_name("第二固定归属协议");second.setProtocol_type("http");second=service.create(second);
        for(ProtocolPointEntity point:points) {point.setId(null);point.setProtocol_id(second.getId());service.createPoint(point);}
        second=service.getById(second.getId());second=service.publish(second.getId(),second.getRow_version());
        var app=new com.cayleywcs.application.entity.ApplicationEntity();app.setId(owner.getId());app.setTenant_id(2L);app.setProtocol_id(second.getId());app.setProtocol_revision_id(second.getPublished_revision_id());app.setEnabled(true);app.setIs_valid(true);app.setDirection("downstream");app.setDevice_type("auto_door");
        SecurityContextHolder.clearContext();
        assertThat(contracts.resolve(app).protocolId()).isEqualTo(second.getId());
        assertThat(service.getById(second.getId()).getTenant_id()).isEqualTo(2L);
        app.setProtocol_revision_id(original.getPublished_revision_id());assertThatThrownBy(()->contracts.resolve(app)).hasMessageContaining("归属");
        app.setProtocol_id(original.getId());assertThatThrownBy(()->contracts.resolve(app)).hasMessageContaining("归属");
    }
    @ParameterizedTest
    @CsvSource({"CONVEYOR_SIM,cmd_DestStation,BOOL","CONVEYOR_SIM,cmd_DestStation,STRING","PALLET_SIM_OPCUA,cmd_TakeCoor_Row,FLOAT32","PALLET_SIM_OPCUA,status_Task,BOOL","DOOR_SIM,cmd_Open,BOOL","CONVEYOR_SIM,cmd_TaskNum,BOOL"})
    void publicationRejectsTypesTheActualNumericDeviceHandlerCannotExecute(String source,String field,String logical) {
        ProtocolEntity draft=service.cloneDraft(protocol(source).getId());
        ProtocolPointEntity point=service.listPoints(draft.getId()).stream().filter(p->p.getField_name().equals(field)).findFirst().orElseThrow();
        point.setLogical_type(logical);point.setData_type(logical);point.setDefault_value(null);
        point.setConstraints(switch(logical){case "STRING"->Map.of("encoding","UTF-8","max_length",32);case "FLOAT32"->Map.of("min",0,"max",2.5);default->Map.of();});service.updatePoint(point);
        ProtocolEntity current=service.getById(draft.getId());
        assertThatThrownBy(()->service.publish(current.getId(),current.getRow_version())).isInstanceOf(com.cayleywcs.common.exception.WcsException.class).hasMessageContaining(field).satisfies(ex->assertThat(((com.cayleywcs.common.exception.WcsException)ex).getCode()).isEqualTo(4223));
        assertThat(service.revisions(draft.getId())).isEmpty();
    }
    @Test void publicationRequiresCompleteStatesAndFixedStackerFaultArrayShape() {
        ProtocolEntity draft=service.cloneDraft(protocol("CONVEYOR_SIM").getId());
        ProtocolPointEntity mode=service.listPoints(draft.getId()).stream().filter(p->p.getField_name().equals("status_Mode")).findFirst().orElseThrow();
        mode.setConstraints(Map.of("min",0,"max",4,"enum",List.of(0,2,3,4)));service.updatePoint(mode);
        ProtocolEntity current=service.getById(draft.getId());assertThatThrownBy(()->service.publish(current.getId(),current.getRow_version())).hasMessageContaining("完整枚举");
        ProtocolEntity arrayDraft=service.cloneDraft(protocol("PALLET_SIM_OPCUA").getId());
        ProtocolPointEntity array=service.listPoints(arrayDraft.getId()).stream().filter(p->p.getField_name().equals("status_ErrorCode")).findFirst().orElseThrow();
        array.setData_type("ARRAY[59] OF INT16");array.setConstraints(Map.of("dimensions",List.of(59),"length",59,"element_type","INT16"));service.updatePoint(array);
        ProtocolEntity arrayCurrent=service.getById(arrayDraft.getId());assertThatThrownBy(()->service.publish(arrayCurrent.getId(),arrayCurrent.getRow_version())).hasMessageContaining("60");
    }
    @Test void taskNumberCommandCannotExceedFeedbackRangeButIntegerNativeWidthsRemainSupported() {
        ProtocolEntity draft=service.cloneDraft(protocol("CONVEYOR_SIM").getId());
        ProtocolPointEntity feedback=service.listPoints(draft.getId()).stream().filter(p->p.getField_name().equals("status_TaskNum")).findFirst().orElseThrow();
        feedback.setConstraints(Map.of("min",0,"max",100));service.updatePoint(feedback);
        ProtocolEntity invalid=service.getById(draft.getId());assertThatThrownBy(()->service.publish(invalid.getId(),invalid.getRow_version())).hasMessageContaining("反馈");
        feedback.setConstraints(Map.of("min",0,"max",65534));service.updatePoint(feedback);
        ProtocolPointEntity number=service.listPoints(draft.getId()).stream().filter(p->p.getField_name().equals("cmd_TaskNum")).findFirst().orElseThrow();
        number.setData_type("INT32");number.setLogical_type("INT32");number.setConstraints(Map.of("min",1,"max",65534,"reserved_values",List.of(0,65535)));service.updatePoint(number);
        ProtocolEntity current=service.getById(draft.getId());assertThat(service.publish(current.getId(),current.getRow_version()).getPublished_revision_id()).isNotNull();
    }
    @Test void existingUnpinnedApplicationCannotAdoptLatestPublishedRevisionOrConnect() {
        ProtocolEntity published=protocol("PALLET_SIM_OPCUA");published=service.publish(published.getId(),published.getRow_version());
        Long appId=jdbc.queryForObject("select id from wcs_application where app_code='PALLET_SIM'",Long.class);
        jdbc.update("update wcs_application set protocol_revision_id=null,configuration_state='active',desired_state='closed' where id=?",appId);
        var old=applications.getById(appId);
        assertThatThrownBy(()->contracts.resolve(old)).hasMessageContaining("显式选择");
        assertThatThrownBy(()->connections.open(appId)).hasMessageContaining("显式选择");
        assertThat(connections.isOpen(appId)).isFalse();
    }
    @Test void previouslyPublishedInvalidHandlerSnapshotCannotBeResolvedOrActivated() throws Exception {
        var app=new com.cayleywcs.application.entity.ApplicationEntity();app.setApp_code("INVALID_HISTORICAL_REVISION");app.setApp_name("旧版坏快照");app.setDevice_type("conveyor");app.setDirection("downstream");app.setEnabled(true);app=applications.create(app);
        ProtocolEntity draft=service.cloneDraft(protocol("CONVEYOR_SIM").getId(),app.getId());draft=service.publish(draft.getId(),draft.getRow_version());
        var snapshot=service.revisions(draft.getId()).getFirst();
        ProtocolPointEntity command=snapshot.getPoints().stream().filter(p->p.getField_name().equals("cmd_DestStation")).findFirst().orElseThrow();
        command.setData_type("BOOL");command.setLogical_type("BOOL");command.setConstraints(Map.of());command.setDefault_value(null);
        jdbc.update("update wcs_protocol_revision set points=? where id=?",new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot.getPoints()),snapshot.getId());
        assertThatThrownBy(()->contracts.resolveRevision(snapshot.getId(),"conveyor")).hasMessageContaining("cmd_DestStation");
        app.setProtocol_id(draft.getId());app.setProtocol_revision_id(snapshot.getId());var invalidApp=app;
        assertThatThrownBy(()->applications.update(invalidApp)).hasMessageContaining("cmd_DestStation");
        assertThat(applications.getById(app.getId()).getProtocol_id()).isNull();
    }
}
