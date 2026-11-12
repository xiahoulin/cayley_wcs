package com.cayleywcs.protocol;

import static org.assertj.core.api.Assertions.*;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.binding.BindingService;
import com.cayleywcs.binding.entity.AppBindingEntity;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:binding_contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
@ActiveProfiles("test-flyway")
@Transactional
class BindingContractIntegrationTest {
    @Autowired ProtocolService protocols;
    @Autowired ProtocolContractService contracts;
    @Autowired ApplicationService apps;
    @Autowired ApplicationMapper appRows;
    @Autowired BindingService bindings;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.mybatis.spring.SqlSessionTemplate sessions;
    @BeforeEach void authenticate() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(1L,"admin","admin","admin",1L,1L),null,List.of())); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    ProtocolEntity protocol(String code) { return protocols.listAll().stream().filter(p -> code.equals(p.getProtocol_code())).findFirst().orElseThrow(); }
    ProtocolEntity publish(String code) { var p=protocol(code); return protocols.publish(p.getId(),p.getRow_version()); }
    ApplicationEntity app(ProtocolEntity p,String direction,String type) {
        ApplicationEntity a=new ApplicationEntity(); a.setApp_code("contract-"+UUID.randomUUID());a.setApp_name("合同测试");
        a.setDevice_type(type);a.setDirection(direction);a.setEnabled(true);a.setConn_params(Map.of());a.setMax_retry(1L);a.setHeartbeat_interval_ms(1000L); a=apps.create(a);var copy=protocols.cloneDraft(p.getId(),a.getId());copy=protocols.publish(copy.getId(),copy.getRow_version());a.setProtocol_id(copy.getId());a.setProtocol_revision_id(copy.getPublished_revision_id());return apps.update(a);
    }
    AppBindingEntity relation(ApplicationEntity up,ApplicationEntity down) { AppBindingEntity row=new AppBindingEntity();row.setUpstream_app_id(up.getId());row.setDownstream_app_id(down.getId());row.setEnabled(true);return row; }

    @Test void exactPublishedContractsPinBindingAndSoftRestoreRevalidates() {
        ApplicationEntity up=app(publish("HTTP_DEVICE_CONTRACT"),"upstream","none");
        ApplicationEntity down=app(publish("PALLET_SIM_OPCUA"),"downstream","stacker");
        var match=contracts.requireMatch(up,down); assertThat(match.compatible()).isTrue();
        var row=bindings.create(relation(up,down));
        assertThat(row.getUpstream_revision_id()).isEqualTo(up.getProtocol_revision_id()); assertThat(row.getDownstream_revision_id()).isEqualTo(down.getProtocol_revision_id());
        assertThat(bindings.isAllowed(up.getId(),down.getId(),1L)).isTrue();
        bindings.delete(row.getId()); assertThat(bindings.isAllowed(up.getId(),down.getId(),1L)).isFalse();
        var restored=bindings.create(relation(up,down)); assertThat(restored.getId()).isEqualTo(row.getId());
        assertThat(bindings.isAllowed(up.getId(),down.getId(),1L)).isTrue();
    }
    @Test void aSingleMismatchInBatchWritesNothingAndKeepsPriorGrant() {
        ApplicationEntity up=app(publish("HTTP_DEVICE_CONTRACT"),"upstream","none");
        ApplicationEntity good=app(publish("DOOR_SIM"),"downstream","auto_door");
        ProtocolEntity mismatch=protocols.cloneDraft(good.getProtocol_id());
        ProtocolPointEntity open=protocols.listPoints(mismatch.getId()).stream().filter(p->p.getField_name().equals("cmd_Open")).findFirst().orElseThrow();
        Map<String,Object> rules=new LinkedHashMap<>(open.getConstraints());rules.put("unit","switch-position");open.setConstraints(rules);protocols.updatePoint(open);
        mismatch=protocols.getById(mismatch.getId()); mismatch=protocols.publish(mismatch.getId(),mismatch.getRow_version());
        ApplicationEntity bad=app(mismatch,"downstream","auto_door");
        bindings.grant(up.getId(),List.of(good.getId()),"dispatch");
        long before=jdbc.queryForObject("select count(*) from wcs_app_binding where upstream_app_id=?",Long.class,up.getId());
        assertThatThrownBy(()->bindings.grant(up.getId(),List.of(good.getId(),bad.getId()),"dispatch")).hasMessageContaining("不匹配");
        assertThat(jdbc.queryForObject("select count(*) from wcs_app_binding where upstream_app_id=?",Long.class,up.getId())).isEqualTo(before);
        assertThat(bindings.grantedDownstreamIds(up.getId(),1L)).containsExactly(good.getId());
    }
    @Test void wrongRolesFixedOwnerAndStalePinnedVersionDenyAccess() {
        ApplicationEntity up=app(publish("HTTP_DEVICE_CONTRACT"),"upstream","none");
        ProtocolEntity physical=publish("DOOR_SIM"); ApplicationEntity down=app(physical,"downstream","auto_door");
        bindings.create(relation(up,down));
        assertThatThrownBy(()->bindings.create(relation(down,up))).hasMessageContaining("方向");
        assertThatThrownBy(()->jdbc.update("update wcs_application set tenant_id=2 where id=?",down.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update wcs_application set protocol_revision_id=? where id=?",up.getProtocol_revision_id(),down.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(bindings.isAllowed(up.getId(),down.getId(),2L)).isFalse();
        var newer=protocols.cloneDraft(down.getProtocol_id());newer=protocols.publish(newer.getId(),newer.getRow_version());
        jdbc.update("update wcs_application set protocol_id=?,protocol_revision_id=? where id=?",newer.getId(),newer.getPublished_revision_id(),down.getId());
        sessions.clearCache();
        assertThat(bindings.isAllowed(up.getId(),down.getId(),1L)).isFalse();
        ApplicationEntity changed=appRows.selectById(down.getId());assertThat(contracts.match(up,changed).compatible()).isTrue();
        assertThatThrownBy(()->bindings.assertAllowed(up.getId(),down.getId(),1L)).hasMessageContaining("授权");
    }
}
