package com.cayleywcs.connection.impl;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.*;
import com.cayleywcs.adapter.seer.SeerPointCatalog;
import com.cayleywcs.device.agv.AgvReadService;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.contract.*;
import com.cayleywcs.telemetry.*;
import com.cayleywcs.telemetry.profile.SensorPointCatalog;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeviceStateServiceImpl implements DeviceStateService {
    private final ApplicationService applications;
    private final ProtocolService protocols;
    private final ProtocolContractService contracts;
    private final ConnectionManager connections;
    private final DeviceExecutionCoordinator coordinator;
    private final SensorApplicationService sensors;
    private final TelemetryService telemetry;
    private final AgvReadService agvs;
    private final JdbcTemplate jdbc;
    public DeviceStateServiceImpl(ApplicationService applications,ProtocolService protocols,ProtocolContractService contracts,
            ConnectionManager connections,DeviceExecutionCoordinator coordinator,SensorApplicationService sensors,TelemetryService telemetry,AgvReadService agvs,JdbcTemplate jdbc) {
        this.applications=applications;this.protocols=protocols;this.contracts=contracts;this.connections=connections;
        this.coordinator=coordinator;this.sensors=sensors;this.telemetry=telemetry;this.agvs=agvs;this.jdbc=jdbc;
    }
    @Override public List<Map<String,Object>> applications() {
        Map<Long,Map<String,Object>> gateways=new HashMap<>();
        return applications.listAll().stream().filter(a->"downstream".equals(a.getDirection()) && !"none".equals(a.getDevice_type())).map(app->{
            var row=summary(app);
            var link=sensors.find(app.getId(),app.getTenant_id());
            if(link!=null) {
                var live=gateways.computeIfAbsent(link.gatewayId(),telemetry::latest);
                row.put("gatewayId",link.gatewayId());row.put("sensorKind",link.sensorKind());row.put("agvAppId",live.get("agvAppId"));row.put("state",sensorState(app,link,live));
            }
            return row;
        }).toList();
    }
    private Map<String,Object> summary(ApplicationEntity app) {
        Map<String,Object> row=new LinkedHashMap<>();var snapshot=connections.cachedSnapshot(app.getId());
        row.put("appId",app.getId());row.put("appCode",app.getApp_code());row.put("appName",app.getApp_name());row.put("deviceType",app.getDevice_type());
        row.put("protocolType",app.getProtocol_id()==null?"":protocols.getById(app.getProtocol_id()).getProtocol_type());row.put("revisionId",app.getProtocol_revision_id());
        row.put("configurationEpoch",app.getConfiguration_epoch());row.put("enabled",app.getEnabled());row.put("workMode",app.getWork_mode());
        row.put("adapterType",com.cayleywcs.adapter.ApplicationAdapterCatalog.type(app));
        row.put("desiredState",app.getDesired_state());row.put("state",app.getProtocol_revision_id()==null?"UNCONFIGURED":snapshot==null?"CLOSED":snapshot.state());
        row.put("connected",snapshot!=null);row.put("lastHeartbeatAt",snapshot==null?null:snapshot.lastHeartbeatAt());
        row.put("retryCount",snapshot==null?0:snapshot.retryCount());return row;
    }
    private String sensorState(ApplicationEntity app,SensorApplicationService.Link link,Map<String,Object> live) {
        if(!Boolean.TRUE.equals(live.get("endpointConfigured")))return "UNCONFIGURED";
        if(!Boolean.TRUE.equals(app.getEnabled()) || !"open".equals(app.getDesired_state()))return "STOPPED";
        if(live.get("sensorHealth") instanceof Map<?,?> health && health.get(SensorPointCatalog.UNITS.get(link.sensorKind()).toString()) instanceof Map<?,?> unit)
            return String.valueOf(unit.get("state"));
        return String.valueOf(live.getOrDefault("connectionState","UNKNOWN"));
    }
    @Override public Map<String,Object> state(Long appId) {
        var app=applications.getById(appId);var result=summary(app);
        if(app.getProtocol_revision_id()==null) {
            result.put("points",List.of());result.put("serverTime",System.currentTimeMillis());result.put("staleAfterMs",6000);
            result.put("settingReason","应用尚未选定已发布协议，请先在应用所属协议下维护点位");return result;
        }
        // Disabled applications still expose their selected point definitions, but cannot be set.
        boolean enabled=Boolean.TRUE.equals(app.getEnabled());app.setEnabled(true);
        var revision=contracts.resolve(app);app.setEnabled(enabled);
        var snapshot=connections.cachedSnapshot(appId);
        var cache=snapshot==null || snapshot.latest()==null?Map.<String,Object>of():snapshot.latest();
        long now=System.currentTimeMillis();
        long staleMs=Math.max(6000,3*(app.getHeartbeat_interval_ms()==null?1000:app.getHeartbeat_interval_ms()));
        boolean fresh=enabled && snapshot!=null && "RUNNING".equals(snapshot.state()) && now-snapshot.lastHeartbeatAt()<=staleMs && connections.matchesConfiguration(app);
        String gate=settingGate(app,snapshot,fresh);
        var link=sensors.find(appId,app.getTenant_id());
        var live=link==null?Map.<String,Object>of():telemetry.latest(link.gatewayId());
        Map<?,?> measurements=live.get("measurements") instanceof Map<?,?> m?m:Map.of();
        if(link!=null) {result.put("gatewayId",link.gatewayId());result.put("sensorKind",link.sensorKind());result.put("state",sensorState(app,link,live));}
        Map<?,?> queries="agv".equals(app.getDevice_type()) && agvs.state(appId).get("queries") instanceof Map<?,?> q?q:Map.of();
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var point:revision.points().stream().sorted(Comparator.comparing(p->p.getSort()==null?0L:p.getSort())).toList()) {
            var row=new LinkedHashMap<String,Object>();String name=point.getField_name();
            boolean readable="response".equals(point.getDirection());
            boolean writable="business".equals(point.getPoint_scope()) && "request".equals(point.getDirection())
                    && !"agv".equals(app.getDevice_type()) && !SensorPointCatalog.isSensor(app.getDevice_type());
            row.put("field",name);row.put("label",point.getSymbol_name()==null?name:point.getSymbol_name());row.put("type",point.getLogical_type());
            row.put("scope",point.getPoint_scope());row.put("address",point.getAddress());row.put("description",point.getDescription());
            row.put("unit",PointContractValidator.constraints(point).getOrDefault("unit",""));row.put("constraints",point.getConstraints());
            row.put("readable",readable);row.put("writable",writable);row.put("canSet",writable && gate.isEmpty());
            row.put("reason",writable?gate:readable?"设备反馈，只读":"驱动内部控制点，使用专用操作");
            if(SeerPointCatalog.NAV_STATION.equals(name) && "seer_agv".equals(app.getAdapter_type()))row.put("reason","请使用连接监控中的导航至站点操作");
            Object value=readable?cache.get(name):null;
            String quality=!readable?"NO_READBACK":value==null?"UNKNOWN":fresh?"GOOD":"STALE";
            Object receivedAt=readable && value!=null && snapshot!=null?snapshot.lastHeartbeatAt():null;
            var field=SeerPointCatalog.field(name);
            if(field!=null) {
                row.put("sourceField",field.key());
                String packet=field.api()==1004?"seer_location":field.api()==1005?"seer_speed":"seer_navigation";
                if(queries.get(packet) instanceof Map<?,?> query && query.get("receivedAt") instanceof String time) {
                    receivedAt=java.time.Instant.parse(time).toEpochMilli();
                    if(value!=null && now-((Number)receivedAt).longValue()>6000)quality="STALE";
                }
            }
            if(link!=null && measurements.get(name) instanceof Map<?,?> sample) {
                quality=String.valueOf(sample.get("quality"));value=sample.get("value");receivedAt=sample.get("receivedAt");
                row.put("raw",sample.get("raw"));row.put("lastGood",sample.get("lastGood"));
                if(!Boolean.TRUE.equals(app.getEnabled()) || !"open".equals(app.getDesired_state()))quality=receivedAt==null?"UNKNOWN":"STALE";
                if(!Boolean.TRUE.equals(live.get("endpointConfigured")))quality="UNCONFIGURED";
            }
            row.put("value","GOOD".equals(quality)?value:null);row.put("sampleValue",value);row.put("quality",quality);row.put("receivedAt",receivedAt);
            rows.add(row);
        }
        result.put("points",rows);result.put("serverTime",now);result.put("staleAfterMs",link!=null || "agv".equals(app.getDevice_type())?6000:staleMs);result.put("settingReason",gate);return result;
    }
    private String settingGate(ApplicationEntity app,ConnectionSnapshot snapshot,boolean fresh) {
        if(!Boolean.TRUE.equals(app.getEnabled()))return "应用已停用";
        if(!"active".equals(app.getConfiguration_state()))return "应用配置正在切换";
        if(!fresh || snapshot==null)return "连接未就绪或状态已过期";
        if(!"maintenance".equals(app.getWork_mode()))return "请先在应用管理切换到维护模式";
        if(jdbc.queryForObject("select count(*) from wcs_task where app_id=? and is_valid=true and status not in ('completed','failed','cancelled')",Long.class,app.getId())>0
                || jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where app_id=?",Long.class,app.getId())>0)return "设备仍有活动任务或任务号占用";
        boolean door="auto_door".equals(app.getDevice_type());Object idle=snapshot.latest().get(door?"status_DoorState":"status_Task");
        if(!(idle instanceof Number n) || !(n.longValue()==0 || door && n.longValue()==1))return "设备尚未确认空闲";
        return "";
    }
    @Override public Map<String,Object> set(Setting request) {
        return coordinator.exclusive(request.appId(),()->{
            var app=applications.getById(request.appId());
            if(!Objects.equals(request.revisionId(),app.getProtocol_revision_id()) || !Objects.equals(request.configurationEpoch(),app.getConfiguration_epoch()))
                throw new WcsException(409,"应用点位版本已变化，请刷新状态后重新设置");
            return connections.writePayload(request.appId(),request.payload());
        });
    }
}
