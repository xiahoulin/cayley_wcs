package com.cayleywcs.telemetry.impl;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.telemetry.SensorApplicationService;
import com.cayleywcs.telemetry.entity.SensorGatewayEntity;
import com.cayleywcs.telemetry.mapper.SensorGatewayMapper;
import com.cayleywcs.telemetry.profile.SensorPointCatalog;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SensorApplicationServiceImpl implements SensorApplicationService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private final JdbcTemplate jdbc;
    private final SensorGatewayMapper gateways;
    private final ApplicationService applications;
    private final ProtocolService protocols;
    private final CurrentUserProvider users;
    public SensorApplicationServiceImpl(JdbcTemplate jdbc,SensorGatewayMapper gateways,ApplicationService applications,
            ProtocolService protocols,CurrentUserProvider users) {
        this.jdbc=jdbc;this.gateways=gateways;this.applications=applications;this.protocols=protocols;this.users=users;
    }
    @Override @Transactional public List<Link> provision(Long gatewayId) {
        if(lifecycle!=null) lifecycle.references();
        Long tenant=users.currentUser().tenantId();
        if(jdbc.queryForList("select id from wcs_sensor_gateway where id=? and tenant_id=? and is_valid=true for update",gatewayId,tenant).isEmpty())
            throw new WcsException(404,"采集网关不存在或不可访问");
        var gateway=gateways.selectById(gatewayId);
        var parent=applications.getById(gateway.getAgv_app_id());
        if(Boolean.TRUE.equals(gateway.getEnabled())) throw new WcsException(409,"请先停止原网关采集，再创建独立应用");
        List<Link> result=new ArrayList<>();
        for(String kind:List.of("vibration_sensor","noise_sensor","infrared_sensor")) {
            var existing=jdbc.query("select app_id,gateway_id,sensor_kind,tenant_id from wcs_sensor_application where gateway_id=? and sensor_kind=? and tenant_id=?",
                    (rs,row)->new Link(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4)),gatewayId,kind,tenant);
            if(!existing.isEmpty()) { applications.getById(existing.getFirst().appId()); result.add(existing.getFirst()); continue; }
            String code="SENSOR_"+gatewayId+"_"+SensorPointCatalog.UNITS.get(kind);
            var app=new ApplicationEntity();app.setApp_code(code);app.setApp_name(parent.getApp_name()+" · "+SensorPointCatalog.NAMES.get(kind));
            app.setDevice_type(kind);app.setDirection("downstream");app.setAdapter_type("dr164_sensor");
            app.setEnabled(true);app.setHeartbeat_interval_ms(1000L);app.setMax_retry(5L);app.setConn_params(Map.of("gatewayId",gatewayId,"sensorKind",kind));
            app=applications.create(app);
            var protocol=new ProtocolEntity();protocol.setProtocol_code(code);protocol.setProtocol_name(SensorPointCatalog.NAMES.get(kind)+"只读点位");
            protocol.setApp_id(app.getId());
            protocol.setProtocol_type(SensorPointCatalog.PROTOCOL);protocol.setStatus("enabled");protocol.setData_format(Map.of());protocol.setTransport_template(Map.of());
            protocol=protocols.create(protocol);
            for(var point:SensorPointCatalog.points(protocol.getId(),kind))protocols.createPoint(point);
            protocol=protocols.getById(protocol.getId());protocol=protocols.publish(protocol.getId(),protocol.getRow_version());
            app.setProtocol_id(protocol.getId());app.setProtocol_revision_id(protocol.getPublished_revision_id());
            app=applications.update(app);
            jdbc.update("insert into wcs_sensor_application(app_id,gateway_id,sensor_kind,tenant_id) values (?,?,?,?)",app.getId(),gatewayId,kind,tenant);
            result.add(new Link(app.getId(),gatewayId,kind,tenant));
        }
        return result;
    }
    @Override public Link find(Long appId,Long tenantId) {
        var rows=jdbc.query("select s.app_id,s.gateway_id,s.sensor_kind,s.tenant_id from wcs_sensor_application s join wcs_sensor_gateway g on g.id=s.gateway_id join wcs_application a on a.id=s.app_id where s.app_id=? and s.tenant_id=? and g.tenant_id=s.tenant_id and g.is_valid=true and a.tenant_id=s.tenant_id and a.is_valid=true and a.device_type=s.sensor_kind",
                (rs,row)->new Link(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4)),appId,tenantId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Override public boolean hasApplications(Long gatewayId) { return jdbc.queryForObject("select count(*) from wcs_sensor_application where gateway_id=?",Long.class,gatewayId)>0; }
    @Override public Set<Integer> activeUnits(SensorGatewayEntity gateway) {
        if(!hasApplications(gateway.getId()))return Boolean.TRUE.equals(gateway.getEnabled())?Set.of(80,1,2):Set.of();
        var kinds=jdbc.queryForList("select s.sensor_kind from wcs_sensor_application s join wcs_application a on a.id=s.app_id where s.gateway_id=? and s.tenant_id=? and a.tenant_id=s.tenant_id and a.is_valid=true and a.enabled=true and a.desired_state='open' and a.configuration_state='active' and a.device_type=s.sensor_kind",String.class,gateway.getId(),gateway.getTenant_id());
        Set<Integer> units=new HashSet<>();for(String kind:kinds)units.add(SensorPointCatalog.UNITS.get(kind));return Set.copyOf(units);
    }
}
