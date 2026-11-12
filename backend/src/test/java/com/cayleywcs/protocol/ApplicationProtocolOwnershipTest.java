package com.cayleywcs.protocol;

import static org.assertj.core.api.Assertions.*;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.DeviceStateService;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:protocol_ownership;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles("test-flyway")
@Transactional
class ApplicationProtocolOwnershipTest {
    @Autowired ApplicationService apps;
    @Autowired ProtocolService protocols;
    @Autowired ProtocolContractService contracts;
    @Autowired ConnectionManager connections;
    @Autowired DeviceStateService states;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void user(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(1L,"admin","admin","admin",1L,1L),null,List.of()));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    ApplicationEntity register(String code){var app=new ApplicationEntity();app.setId(999999L);app.setApp_code(code);app.setApp_name(code);app.setDevice_type("conveyor");app.setDirection("downstream");app.setConn_params(Map.of());return apps.create(app);}
    ProtocolEntity source(){return protocols.listAll().stream().filter(p->"CONVEYOR_SIM".equals(p.getProtocol_code())).findFirst().orElseThrow();}
    ProtocolEntity publishFor(ApplicationEntity app){var p=protocols.cloneDraft(source().getId(),app.getId());return protocols.publish(p.getId(),p.getRow_version());}
    @Test void platformIssuesIdentityBeforeProtocolAndUnconfiguredAppsRemainVisible() {
        var app=register("REGISTERED");assertThat(app.getId()).isNotEqualTo(999999L);assertThat(app.getApp_key()).isNotBlank();assertThat(app.getProtocol_id()).isNull();
        assertThat(app.getConfiguration_state()).isEqualTo("awaiting_contract");
        assertThat(states.applications()).anySatisfy(row->{assertThat(row.get("appId")).isEqualTo(app.getId());assertThat(row.get("state")).isEqualTo("UNCONFIGURED");});
        assertThat(states.state(app.getId()).get("points")).isEqualTo(List.of());
        assertThatThrownBy(()->connections.open(app.getId())).hasMessageContaining("尚未配置协议");
        var p=publishFor(app);app.setProtocol_id(p.getId());app.setProtocol_revision_id(p.getPublished_revision_id());var active=apps.update(app);
        assertThat(active.getConfiguration_state()).isEqualTo("active");assertThat(contracts.resolve(active).protocolId()).isEqualTo(p.getId());
        assertThat(active.getApp_key()).isEqualTo(app.getApp_key());
    }
    @Test void sameTenantCannotSelectAnotherApplicationsProtocolOrMovePoints() {
        var a=register("OWNER_A");var b=register("OWNER_B");var p=publishFor(a);
        b.setProtocol_id(p.getId());b.setProtocol_revision_id(p.getPublished_revision_id());
        assertThatThrownBy(()->apps.update(b)).hasMessageContaining("所属应用");
        assertThatThrownBy(()->jdbc.update("update wcs_application set protocol_id=? where id=?",p.getId(),b.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var draft=protocols.cloneDraft(p.getId(),a.getId());draft.setApp_id(b.getId());assertThatThrownBy(()->protocols.update(draft)).hasMessageContaining("所属应用不能修改");
        var point=protocols.listPoints(draft.getId()).getFirst();point.setProtocol_id(source().getId());assertThatThrownBy(()->protocols.updatePoint(point)).hasMessageContaining("点位不能移动");
    }
    @Test void pointAndRevisionForeignKeysEnforceProtocolAndTenantTogether() {
        var app=register("FK_OWNER");var p=publishFor(app);
        assertThatThrownBy(()->jdbc.update("update wcs_protocol_point set tenant_id=2 where protocol_id=?",p.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update wcs_application set protocol_id=?,protocol_revision_id=? where id=?",source().getId(),p.getPublished_revision_id(),app.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from wcs_protocol where id=?",p.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void createRequiresOwnerAndCloneMakesAnIndependentDefinition() {
        var p=new ProtocolEntity();p.setProtocol_code("MISSING_OWNER");p.setProtocol_name("缺失应用");p.setProtocol_type("sim");
        assertThatThrownBy(()->protocols.create(p)).hasMessageContaining("所属应用");
        var a=register("COPY_A");var b=register("COPY_B");var first=publishFor(a);var copy=protocols.cloneDraft(first.getId(),b.getId());
        assertThat(copy.getApp_id()).isEqualTo(b.getId());assertThat(copy.getLifecycle_state()).isEqualTo("draft");
        var point=protocols.listPoints(copy.getId()).getFirst();point.setSymbol_name("独立名称");protocols.updatePoint(point);
        assertThat(protocols.listPoints(first.getId())).noneMatch(row->"独立名称".equals(row.getSymbol_name()));
        p.setApp_id(a.getId());protocols.create(p);p.setApp_id(b.getId());assertThat(protocols.create(p).getApp_id()).isEqualTo(b.getId());
    }
}
