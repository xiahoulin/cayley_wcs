package com.cayleywcs.device.agv.impl;

import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.adapter.seer.SeerTcpAdapter;
import com.cayleywcs.adapter.seer.SeerPointCatalog;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.device.agv.AgvReadService;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgvReadServiceImpl implements AgvReadService {
    private final ApplicationService applications;
    private final ProtocolService protocols;
    private final ConnectionManager connections;
    private final DeviceEndpoints endpoints;
    private final com.cayleywcs.system.CurrentUserProvider users;
    private final ObjectMapper json = new ObjectMapper();
    public AgvReadServiceImpl(ApplicationService applications, ProtocolService protocols,
            ConnectionManager connections, DeviceEndpoints endpoints,com.cayleywcs.system.CurrentUserProvider users) {
        this.applications=applications; this.protocols=protocols; this.connections=connections; this.endpoints=endpoints;
        this.users=users;
    }
    @Override @Transactional public Map<String,Object> create(Create request) {
        if (request.appCode()==null || !request.appCode().matches("[A-Za-z][A-Za-z0-9_-]{0,47}")
                || request.appName()==null || request.appName().isBlank() || request.appName().length()>100)
            throw new WcsException(4223,"车辆编码或名称无效");
        endpoints.resolveForTenant(request.endpointRef(),users.currentUser().tenantId());
        ApplicationEntity app = new ApplicationEntity();
        app.setApp_code(request.appCode()); app.setApp_name(request.appName()); app.setDevice_type("agv"); app.setDirection("downstream");
        app.setAdapter_type("seer_agv");
        app.setConn_params(Map.of("endpointRef",request.endpointRef())); app.setEnabled(true); app.setHeartbeat_interval_ms(1000L); app.setMax_retry(5L);
        app = applications.create(app);
        ProtocolEntity protocol = new ProtocolEntity();
        protocol.setApp_id(app.getId());
        protocol.setProtocol_code("AGV_RO_"+request.appCode()); protocol.setProtocol_name(request.appName()+" 只读状态");
        protocol.setProtocol_type("tcp"); protocol.setStatus("enabled");
        protocol.setData_format(Map.of()); protocol.setTransport_template(Map.of()); protocol = protocols.create(protocol);
        for(var point:SeerPointCatalog.points(protocol.getId()))protocols.createPoint(point);
        protocol = protocols.getById(protocol.getId()); protocol = protocols.publish(protocol.getId(),protocol.getRow_version());
        app.setProtocol_id(protocol.getId()); app.setProtocol_revision_id(protocol.getPublished_revision_id());
        app = applications.update(app);
        return state(app.getId());
    }
    @Override @Transactional public Map<String,Object> upgrade(Long appId) {
        var app=applications.getById(appId);
        if(!"agv".equals(app.getDevice_type()))throw new WcsException(4223,"不是 AGV 应用");
        var current=protocols.revisions(app.getProtocol_id()).stream().filter(r->r.getId().equals(app.getProtocol_revision_id())).findFirst().orElseThrow();
        if(current.getPoints().stream().anyMatch(p->Long.valueOf(2).equals(p.getTemplate_version()) || Long.valueOf(3).equals(p.getTemplate_version())))return state(appId);
        boolean reopen="open".equals(app.getDesired_state());
        var protocol=new ProtocolEntity();protocol.setProtocol_code("AGV_POINTS_"+appId);protocol.setProtocol_name(app.getApp_name()+" 状态点位");
        protocol.setApp_id(appId);
        protocol.setProtocol_type("tcp");protocol.setStatus("enabled");protocol.setData_format(Map.of());protocol.setTransport_template(Map.of());
        protocol=protocols.create(protocol);
        for(var point:SeerPointCatalog.points(protocol.getId()))protocols.createPoint(point);
        protocol=protocols.getById(protocol.getId());protocol=protocols.publish(protocol.getId(),protocol.getRow_version());
        app.setAdapter_type("seer_agv");
        app.setProtocol_id(protocol.getId());app.setProtocol_revision_id(protocol.getPublished_revision_id());applications.update(app);
        if(reopen) {
            applications.updateDesiredState(appId,"open");
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit(){connections.open(appId);}
            });
        }
        return state(appId);
    }
    @Override public List<Map<String,Object>> list() {
        return applications.listAll().stream().filter(app->"agv".equals(app.getDevice_type())).map(app->state(app.getId())).toList();
    }
    @Override public Map<String,Object> state(Long appId) {
        ApplicationEntity app = applications.getById(appId);
        if (!"agv".equals(app.getDevice_type())) throw new WcsException(4223,"不是 AGV 应用");
        Map<String,Object> result = cachedState(appId);
        result.put("appId",appId); result.put("appCode",app.getApp_code()); result.put("appName",app.getApp_name());
        result.put("endpointRef",app.getConn_params().getOrDefault("endpointRef",""));
        result.put("motionEnabled",app.getProtocol_revision_id()!=null && protocols.revisions(app.getProtocol_id()).stream()
                .filter(r->r.getId().equals(app.getProtocol_revision_id())).flatMap(r->r.getPoints().stream())
                .anyMatch(p->SeerPointCatalog.NAV_STATION.equals(p.getField_name())));
        return result;
    }
    private Map<String,Object> cachedState(Long appId) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("motionEnabled",false); result.put("connectionState","CLOSED"); result.put("quality","UNKNOWN");
        result.put("queries",Map.of()); result.put("ageMs",null);
        try {
            var snapshot=connections.cachedSnapshot(appId);
            if(snapshot==null || snapshot.latest()==null)return result;
            result.put("connectionState",snapshot.state());
            Map<String,Object> queries = new LinkedHashMap<>();
            long oldest = Long.MAX_VALUE;
            for (var entry : snapshot.latest().entrySet()) {
                if (!SeerTcpAdapter.FIELDS.containsKey(entry.getKey()) || !(entry.getValue() instanceof String text)) continue;
                var node=json.readTree(text);
                long received=Instant.parse(node.path("receivedAt").asText()).toEpochMilli();
                oldest=Math.min(oldest,received);
                queries.put(entry.getKey(),json.convertValue(node,Map.class));
            }
            result.put("queries",queries);
            if (!queries.isEmpty()) {
                long age=Math.max(0,System.currentTimeMillis()-oldest); result.put("ageMs",age);
                result.put("quality",age>6000?"STALE":queries.size()==3 && "RUNNING".equals(snapshot.state())?"GOOD":"UNKNOWN");
            }
        } catch (java.io.IOException | java.time.DateTimeException ex) { result.put("quality","PROTOCOL_ERROR"); }
        return result;
    }
    @Override public Map<String,Object> context(Long appId) {
        Map<String,Object> snapshot=cachedState(appId);
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("agvAppId",appId); context.put("capturedAt",System.currentTimeMillis());
        context.put("quality",snapshot.get("quality")); context.put("connectionState",snapshot.get("connectionState"));
        if(snapshot.get("ageMs")!=null) context.put("ageMs",snapshot.get("ageMs"));
        context.put("queries",snapshot.get("queries"));
        return context;
    }
}
