package com.cayleywcs.telemetry.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.telemetry.TelemetryService;
import com.cayleywcs.telemetry.SensorApplicationService;
import com.cayleywcs.telemetry.entity.*;
import com.cayleywcs.telemetry.mapper.*;
import com.cayleywcs.telemetry.polling.SensorPollingService;
import com.cayleywcs.telemetry.profile.SensorProfile;
import com.cayleywcs.telemetry.storage.TelemetryBuffer;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TelemetryServiceImpl implements TelemetryService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private final SensorGatewayMapper gateways;
    private final TelemetryLatestMapper latest;
    private final TelemetryCycleMapper cycles;
    private final ApplicationService applications;
    private final CurrentUserProvider users;
    private final DeviceEndpoints endpoints;
    private final SensorPollingService polling;
    private final TelemetryBuffer buffer;
    private final SensorApplicationService sensorApplications;
    public TelemetryServiceImpl(SensorGatewayMapper gateways,TelemetryLatestMapper latest,TelemetryCycleMapper cycles,
            ApplicationService applications,CurrentUserProvider users,DeviceEndpoints endpoints,SensorPollingService polling,TelemetryBuffer buffer,SensorApplicationService sensorApplications) {
        this.gateways=gateways; this.latest=latest; this.cycles=cycles; this.applications=applications;
        this.users=users; this.endpoints=endpoints; this.polling=polling; this.buffer=buffer;
        this.sensorApplications=sensorApplications;
    }
    private SensorGatewayEntity required(Long id) {
        if(id==null)throw new WcsException(ErrorCode.BAD_REQUEST,"网关 ID 必填");
        var row=gateways.selectOne(new QueryWrapper<SensorGatewayEntity>().eq("id",id).eq("tenant_id",users.currentUser().tenantId()).eq("is_valid",true));
        if(row==null)throw new WcsException(ErrorCode.NOT_FOUND,"网关不存在或不可访问");
        applications.getById(row.getAgv_app_id());
        return row;
    }
    private void version(SensorGatewayEntity row,Long expected) {
        if(!Objects.equals(row.getConfig_version(),expected))throw new WcsException(ErrorCode.CONFLICT,"配置已变化，请刷新");
    }
    private void changed(Long id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { polling.stop(id); }
        });
    }
    @Override @Transactional public SensorGatewayEntity configure(Configure request) {
        if(lifecycle!=null) lifecycle.references();
        request=new Configure(request.id(),request.agvAppId(),request.endpointRef(),
                request.infraredOffset()==null?0:request.infraredOffset(),request.infraredMin()==null?0:request.infraredMin(),
                request.infraredMax()==null?600:request.infraredMax(),request.expectedVersion());
        var app=applications.getById(request.agvAppId());
        if(!"agv".equals(app.getDevice_type()))throw new WcsException(4223,"采集网关必须绑定 AGV");
        if(request.infraredOffset()==null || (request.infraredOffset()!=0 && request.infraredOffset()!=1)
                || request.infraredMin()==null || request.infraredMax()==null
                || !Double.isFinite(request.infraredMin()) || !Double.isFinite(request.infraredMax())
                || request.infraredMin()<0 || request.infraredMax()<=request.infraredMin() || request.infraredMax()>6553.5)
            throw new WcsException(4223,"必须明确红外零基寄存器及已核实的非负量程");
        var endpoint=endpoints.findForTenant(request.endpointRef(),app.getTenant_id());
        // A reference alias must not create a second TCP owner of the same serial gateway.
        for(var other:gateways.selectList(new QueryWrapper<SensorGatewayEntity>().eq("is_valid",true))) {
            if(Objects.equals(other.getId(),request.id()))continue;
            if(Objects.equals(other.getAgv_app_id(),request.agvAppId()))throw new WcsException(ErrorCode.CONFLICT,"该车辆已配置采集网关");
            var location=endpoints.find(other.getEndpoint_ref());
            if(location.isPresent() && endpoint.isPresent() && location.get().host().equalsIgnoreCase(endpoint.get().host()) && location.get().port()==endpoint.get().port())
                throw new WcsException(ErrorCode.CONFLICT,"该采集端点已被占用");
        }
        SensorGatewayEntity row;
        if(request.id()==null) {
            if(gateways.selectCount(new QueryWrapper<SensorGatewayEntity>().eq("is_valid",true))>=32)
                throw new WcsException(ErrorCode.CONFLICT,"首期最多配置 32 个采集网关");
            row=new SensorGatewayEntity(); Audits.fillCreate(row,users.currentUser());
            row.setConfig_version(1L); row.setEnabled(false); row.setAgv_app_id(app.getId());
        } else {
            row=required(request.id()); version(row,request.expectedVersion());
            if(!sensorApplications.activeUnits(row).isEmpty())throw new WcsException(ErrorCode.CONFLICT,"请先在连接监控关闭该网关下的传感器应用，再修改档案");
            if(!Objects.equals(row.getAgv_app_id(),request.agvAppId()))throw new WcsException(4223,"网关不能迁移到另一车辆");
            if(!Objects.equals(row.getEndpoint_ref(),request.endpointRef())) { row.setPower_on_at(null); row.setPower_evidence(null); }
            row.setConfig_version(row.getConfig_version()+1);
        }
        row.setEndpoint_ref(request.endpointRef()); row.setInfrared_offset(request.infraredOffset());
        row.setInfrared_min(request.infraredMin()); row.setInfrared_max(request.infraredMax()); Audits.touch(row);
        if(request.id()==null)gateways.insert(row);
        else {
            int changed=gateways.update(row,new UpdateWrapper<SensorGatewayEntity>().eq("id",row.getId()).eq("config_version",request.expectedVersion()));
            if(changed!=1)throw new WcsException(ErrorCode.CONFLICT,"配置已变化，请刷新");
            changed(row.getId());
        }
        return row;
    }
    @Override @Transactional public SensorGatewayEntity enable(Enable request) {
        if(lifecycle!=null) lifecycle.references();
        var row=required(request.id()); version(row,request.expectedVersion());
        if(sensorApplications.hasApplications(row.getId()))throw new WcsException(ErrorCode.CONFLICT,"该网关已拆分应用，请在连接监控分别启停各传感器");
        if(request.enabled())endpoints.resolveForTenant(row.getEndpoint_ref(),row.getTenant_id());
        int count=gateways.update(null,new UpdateWrapper<SensorGatewayEntity>().eq("id",row.getId()).eq("config_version",request.expectedVersion())
                .set("enabled",request.enabled()).set("config_version",row.getConfig_version()+1).set("last_update_time",java.time.LocalDateTime.now()));
        if(count!=1)throw new WcsException(ErrorCode.CONFLICT,"配置已变化，请刷新");
        changed(row.getId()); return gateways.selectById(row.getId());
    }
    @Override @Transactional public SensorGatewayEntity power(Power request) {
        var row=required(request.id()); version(row,request.expectedVersion());
        if(request.powerOnAt()!=null && (request.powerOnAt()<0 || request.powerOnAt()>System.currentTimeMillis()
                || request.evidence()==null || request.evidence().isBlank() || request.evidence().length()>500))
            throw new WcsException(4223,"可信供电记录必须包含过去时间及来源说明");
        int count=gateways.update(null,new UpdateWrapper<SensorGatewayEntity>().eq("id",row.getId()).eq("config_version",request.expectedVersion())
                .set("power_on_at",request.powerOnAt()).set("power_evidence",request.powerOnAt()==null?null:request.evidence().trim())
                .set("config_version",row.getConfig_version()+1).set("last_update_time",java.time.LocalDateTime.now()));
        if(count!=1)throw new WcsException(ErrorCode.CONFLICT,"配置已变化，请刷新");
        changed(row.getId()); return gateways.selectById(row.getId());
    }
    @Override public List<SensorGatewayEntity> list(Long agvAppId) {
        var query=new QueryWrapper<SensorGatewayEntity>().eq("tenant_id",users.currentUser().tenantId()).eq("is_valid",true);
        if(agvAppId!=null){applications.getById(agvAppId); query.eq("agv_app_id",agvAppId);}
        return gateways.selectList(query.orderByAsc("id"));
    }
    @Override public Map<String,Object> latest(Long id) {
        var gateway=required(id);
        var activeUnits=sensorApplications.activeUnits(gateway);
        Map<String,Object> result=new LinkedHashMap<>(polling.live(id));
        Map<String,Object> samples=new LinkedHashMap<>(), good=new LinkedHashMap<>();
        if(Objects.equals(result.get("configVersion"),gateway.getConfig_version()) && result.get("measurements") instanceof Map<?,?> live) {
            live.forEach((key,value)->samples.put(key.toString(),value));
            if(result.get("lastGood") instanceof Map<?,?> saved)saved.forEach((key,value)->good.put(key.toString(),value));
        } else for(var row:latest.selectList(new QueryWrapper<TelemetryLatestEntity>().eq("gateway_id",id).eq("tenant_id",gateway.getTenant_id()))) {
            if(Objects.equals(row.getConfig_version(),gateway.getConfig_version())) {
                samples.put(row.getMetric(),row.getSample());
                if(row.getLast_good()!=null && !row.getLast_good().isEmpty())good.put(row.getMetric(),row.getLast_good());
            }
        }
        Map<String,Object> decorated=new LinkedHashMap<>();
        for(var block:SensorProfile.plan(gateway.getInfrared_offset()))for(var metric:block.metrics()) {
            Map<String,Object> sample=new LinkedHashMap<>();
            if(samples.get(metric) instanceof Map<?,?> stored)stored.forEach((key,value)->sample.put(key.toString(),value));
            long age=sample.get("receivedAt") instanceof Number time?Math.max(0,System.currentTimeMillis()-time.longValue()):Long.MAX_VALUE;
            sample.put("metric",metric); sample.put("unit",SensorProfile.unit(metric)); sample.put("ageMs",age==Long.MAX_VALUE?null:age);
            sample.put("sourceQuality",sample.getOrDefault("quality","UNKNOWN"));
            if(age==Long.MAX_VALUE)sample.put("quality","UNKNOWN");
            else if(age>SensorProfile.STALE_MS || !activeUnits.contains(block.unit()))sample.put("quality","STALE");
            sample.put("lastGood",good.get(metric));
            decorated.put(metric,sample);
        }
        if(activeUnits.isEmpty())result.put("connectionState","STOPPED");
        result.put("applicationManaged",sensorApplications.hasApplications(id));
        result.put("activeUnits",activeUnits);
        result.put("gatewayId",id); result.put("configVersion",gateway.getConfig_version()); result.put("measurements",decorated);
        result.put("agvAppId",gateway.getAgv_app_id());
        result.put("endpointConfigured",endpoints.findForTenant(gateway.getEndpoint_ref(),gateway.getTenant_id()).isPresent());
        result.remove("lastGood"); result.put("storage",buffer.status(id)); result.put("serverTime",System.currentTimeMillis());
        return result;
    }
    @Override public PageData<TelemetryCycleEntity> history(History request) {
        var gateway=required(request.gatewayId());
        if(request.limit()<1 || request.limit()>100 || request.beforeId()!=null && request.beforeId()<1)
            throw new WcsException(4223,"历史每页必须为 1–100 条");
        long to=request.toAt()==null?System.currentTimeMillis():request.toAt();
        long from=request.fromAt()==null?to-24*60*60*1000L:request.fromAt();
        if(from>to || to-from>7L*24*60*60*1000)throw new WcsException(4223,"单次历史范围最多 7 天");
        var query=new QueryWrapper<TelemetryCycleEntity>().eq("gateway_id",gateway.getId()).eq("tenant_id",gateway.getTenant_id()).between("finished_at",from,to);
        if(request.beforeId()!=null)query.lt("id",request.beforeId());
        long count=cycles.selectCount(query);
        return new PageData<>(cycles.selectList(query.orderByDesc("id").last("limit "+request.limit())),count);
    }
}
