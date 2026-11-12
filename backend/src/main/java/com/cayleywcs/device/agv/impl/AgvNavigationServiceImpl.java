package com.cayleywcs.device.agv.impl;

import com.cayleywcs.adapter.seer.*;
import com.cayleywcs.application.*;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.ResourceLifecycleGuard;
import com.cayleywcs.connection.*;
import com.cayleywcs.device.agv.AgvNavigationService;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.system.CurrentUserProvider;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgvNavigationServiceImpl implements AgvNavigationService {
    private final ApplicationService apps;
    private final ApplicationConfigurationService configuration;
    private final ProtocolService protocols;
    private final ProtocolContractService contracts;
    private final ConnectionManager connections;
    private final DeviceExecutionCoordinator coordinator;
    private final ResourceLifecycleGuard lifecycle;
    private final CurrentUserProvider users;
    private final JdbcTemplate jdbc;
    public AgvNavigationServiceImpl(ApplicationService apps,ApplicationConfigurationService configuration,ProtocolService protocols,
            ProtocolContractService contracts,ConnectionManager connections,DeviceExecutionCoordinator coordinator,
            ResourceLifecycleGuard lifecycle,CurrentUserProvider users,JdbcTemplate jdbc) {
        this.apps=apps;this.configuration=configuration;this.protocols=protocols;this.contracts=contracts;
        this.connections=connections;this.coordinator=coordinator;this.lifecycle=lifecycle;this.users=users;this.jdbc=jdbc;
    }
    private ApplicationEntity app(Long id) {
        if(id==null)throw new WcsException(4223,"缺少应用 AppID");
        var app=apps.getById(id);
        if(!"seer_agv".equals(app.getAdapter_type()) || !"agv".equals(app.getDevice_type()) || !"downstream".equals(app.getDirection()))
            throw new WcsException(4223,"该应用不是仙工 AGV");
        return app;
    }
    private boolean canOperate() {return "admin".equals(users.currentUser().userRole());}
    private void operator() {if(!canOperate())throw new WcsException(403,"仅管理员可下发或核对 AGV 导航指令");}
    private boolean enabled(ApplicationEntity app) {
        boolean active=Boolean.TRUE.equals(app.getEnabled());app.setEnabled(true);
        try {return contracts.resolve(app).points().stream().anyMatch(p->SeerPointCatalog.NAV_STATION.equals(p.getField_name()));}
        finally {app.setEnabled(active);}
    }
    @Override public Map<String,Object> enable(Long id) {
        operator();
        return configuration.update(id,()->{
            var app=app(id);if(enabled(app))return state(id);
            configuration.assertIdle(app);
            var original=contracts.resolve(app);
            if(original.points().stream().noneMatch(p->Long.valueOf(2).equals(p.getTemplate_version())))
                throw new WcsException(409,"请先升级仙工状态点位合同");
            boolean reopen="open".equals(app.getDesired_state());
            var protocol=protocols.cloneDraft(app.getProtocol_id());
            protocol.setProtocol_name(app.getApp_name()+" 状态与站点导航");protocols.update(protocol);
            for(var point:protocols.listPoints(protocol.getId())) {point.setTemplate_version(3L);protocols.updatePoint(point);}
            for(var point:SeerPointCatalog.navigationPoints(protocol.getId()))protocols.createPoint(point);
            protocol=protocols.getById(protocol.getId());protocol=protocols.publish(protocol.getId(),protocol.getRow_version());
            app.setProtocol_id(protocol.getId());app.setProtocol_revision_id(protocol.getPublished_revision_id());apps.update(app);
            if(reopen) {apps.updateDesiredState(id,"open");ResourceLifecycleGuard.afterCommit(()->connections.open(id));}
            return state(id);
        });
    }
    @Override public Map<String,Object> state(Long id) {
        var app=app(id);Map<String,Object> result=new LinkedHashMap<>();
        result.put("appId",id);result.put("revisionId",app.getProtocol_revision_id());result.put("configurationEpoch",app.getConfiguration_epoch());
        result.put("navigationEnabled",app.getProtocol_revision_id()!=null && enabled(app));result.put("canOperate",canOperate());
        var records=jdbc.queryForList("select * from wcs_agv_navigation_command where app_id=? order by id desc limit 1",id);
        result.put("lastCommand",records.isEmpty()?null:view(records.getFirst()));return result;
    }
    @Override public Map<String,Object> send(Command request) {
        operator();SeerNavigationSession.station(request.stationName());
        if(request.appId()==null || request.revisionId()==null || request.configurationEpoch()==null || request.requestId()==null
                || !request.requestId().matches("[A-Za-z0-9_-]{8,64}"))throw new WcsException(4223,"缺少有效请求编号或应用版本");
        return coordinator.exclusive(request.appId(),()->{
            var app=app(request.appId());
            var previous=find(app.getId(),request.requestId());
            if(previous!=null) {
                if(!Objects.equals(previous.get("station_name"),request.stationName())
                        || ((Number)previous.get("protocol_revision_id")).longValue()!=request.revisionId()
                        || ((Number)previous.get("configuration_epoch")).longValue()!=request.configurationEpoch())
                    throw new WcsException(409,"相同请求编号不能用于不同的导航参数");
                return view(previous);
            }
            if(!Objects.equals(app.getProtocol_revision_id(),request.revisionId()) || !Objects.equals(app.getConfiguration_epoch(),request.configurationEpoch()))
                throw new WcsException(409,"应用配置已变化，请重新打开导航窗口");
            if(!Boolean.TRUE.equals(app.getEnabled()) || !"active".equals(app.getConfiguration_state()) || "paused".equals(app.getWork_mode()))
                throw new WcsException(409,"应用停用、暂停或配置未就绪，不能下发导航");
            if(!enabled(app))throw new WcsException(409,"应用当前协议尚未启用站点导航");
            contracts.validatePayload(app,Map.of(SeerPointCatalog.NAV_STATION,request.stationName()));
            configuration.assertIdle(app);
            return connections.withSeerNavigation(app.getId(),adapter->{
                adapter.assertNavigationIdle();
                // Commit before writing to the socket. A crash preserves an unresolved fence and cannot trigger a replay.
                lifecycle.referenceWrite(()->{
                    jdbc.queryForObject("select id from wcs_application where id=? for update",Long.class,app.getId());
                    if(unresolved(app.getId()))throw new WcsException(409,"上一条导航仍待核对，请确认车辆停稳后解除下发锁定");
                    jdbc.update("insert into wcs_agv_navigation_command(app_id,protocol_revision_id,configuration_epoch,request_id,station_name,status,message,created_at,creator,tenant_id) values(?,?,?,?,?,'sending',?,?,?,?)",
                            app.getId(),request.revisionId(),request.configurationEpoch(),request.requestId(),request.stationName(),
                            "导航已登记，发送结果待确认",System.currentTimeMillis(),String.valueOf(users.currentUser().userId()),app.getTenant_id());
                    return null;
                });
                var result=adapter.navigate(request.stationName());
                jdbc.update("update wcs_agv_navigation_command set status=?,sent=?,ret_code=?,message=?,received_at=? where app_id=? and request_id=? and status='sending'",
                        result.status(),result.sent(),result.retCode(),result.message(),result.receivedAt(),app.getId(),request.requestId());
                return view(Objects.requireNonNull(find(app.getId(),request.requestId())));
            });
        });
    }
    @Override public Map<String,Object> acknowledge(Acknowledge request) {
        operator();
        if(request.appId()==null || request.requestId()==null)throw new WcsException(4223,"缺少应用或请求编号");
        return coordinator.exclusive(request.appId(),()->{
            var app=app(request.appId());var row=find(app.getId(),request.requestId());
            if(row==null)throw new WcsException(404,"导航记录不存在");
            if(row.get("acknowledged_at")!=null)return view(row);
            return connections.withSeerNavigation(app.getId(),adapter->{
                adapter.assertNavigationIdle();
                jdbc.update("update wcs_agv_navigation_command set acknowledged_at=? where app_id=? and request_id=?",System.currentTimeMillis(),app.getId(),request.requestId());
                return view(Objects.requireNonNull(find(app.getId(),request.requestId())));
            });
        });
    }
    private boolean unresolved(Long appId) {
        return jdbc.queryForObject("select count(*) from wcs_agv_navigation_command where app_id=? and status in ('sending','accepted','unknown') and acknowledged_at is null",Long.class,appId)>0;
    }
    private Map<String,Object> find(Long id,String requestId) {
        var rows=jdbc.queryForList("select * from wcs_agv_navigation_command where app_id=? and request_id=?",id,requestId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("requestId",row.get("request_id"));result.put("stationName",row.get("station_name"));result.put("sent",row.get("sent"));
        result.put("status",row.get("status"));result.put("retCode",row.get("ret_code"));result.put("message",row.get("message"));
        result.put("createdAt",row.get("created_at"));result.put("receivedAt",row.get("received_at"));
        result.put("needsAcknowledgement",row.get("acknowledged_at")==null && Set.of("sending","accepted","unknown").contains(row.get("status")));
        return result;
    }
}
