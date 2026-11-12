package com.cayleywcs.adapter.sensor;

import com.cayleywcs.adapter.*;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.telemetry.SensorApplicationService;
import com.cayleywcs.telemetry.mapper.SensorGatewayMapper;
import com.cayleywcs.telemetry.polling.SensorPollingService;
import com.cayleywcs.telemetry.profile.*;
import java.util.*;

/** Logical subscriptions share one physical DR164 owner. No socket or write command belongs to an application. */
public class SensorGatewayAdapter extends AbstractProtocolAdapter {
    private final SensorPollingService polling;
    private final SensorApplicationService applications;
    private final SensorGatewayMapper gateways;
    private final DeviceEndpoints endpoints;
    private SensorApplicationService.Link link;
    public SensorGatewayAdapter(SensorPollingService polling,SensorApplicationService applications,SensorGatewayMapper gateways,DeviceEndpoints endpoints) {
        this.polling=polling;this.applications=applications;this.gateways=gateways;this.endpoints=endpoints;
    }
    @Override public String protocolType(){return SensorPointCatalog.PROTOCOL;}
    @Override protected void doConnect(AdapterContext ctx) {
        link=applications.find(ctx.application().getId(),ctx.application().getTenant_id());
        if(link==null || !Objects.equals(ctx.connParamLong("gatewayId",-1),link.gatewayId()) || !ctx.connParam("sensorKind","").equals(link.sensorKind()))
            throw new ProtocolException(4223,"传感器应用未关联正确的共享网关");
        var gateway=gateways.selectById(link.gatewayId());
        endpoints.resolveForTenant(gateway.getEndpoint_ref(),link.tenantId());
    }
    @Override protected void doDisconnect(){ }
    @Override public boolean heartbeat(){return isConnected();}
    @Override public Map<String,Object> readAll(List<ProtocolPointEntity> points) {
        requireConnected();
        Map<String,Object> result=new LinkedHashMap<>();
        var gateway=gateways.selectById(link.gatewayId());
        var live=polling.live(link.gatewayId());
        Object data=Objects.equals(live.get("configVersion"),gateway.getConfig_version())?live.get("measurements"):null;
        for(var point:points) {
            if(!SensorPointCatalog.metrics(link.sensorKind()).contains(point.getField_name()) || !"response".equals(point.getDirection()))
                throw new ProtocolException(4223,"未定义的只读传感器点位");
            Object value=null;
            if(data instanceof Map<?,?> samples && samples.get(point.getField_name()) instanceof Map<?,?> sample
                    && "GOOD".equals(sample.get("quality")) && sample.get("receivedAt") instanceof Number time
                    && System.currentTimeMillis()-time.longValue()<=SensorProfile.STALE_MS) value=sample.get("value");
            result.put(point.getField_name(),value);
        }
        return result;
    }
    @Override public Object read(ProtocolPointEntity point){return readAll(List.of(point)).get(point.getField_name());}
    @Override public void write(ProtocolPointEntity point,Object value){throw new ProtocolException(4223,"传感器测量点位仅可读取");}
}
