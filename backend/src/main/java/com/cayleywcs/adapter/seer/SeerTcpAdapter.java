package com.cayleywcs.adapter.seer;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolException;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class SeerTcpAdapter extends AbstractProtocolAdapter {
    public static final Map<String,Integer> FIELDS = Map.of("seer_location",1004,"seer_speed",1005,"seer_navigation",1020);
    private final DeviceEndpoints endpoints;
    private final ObjectMapper json = new ObjectMapper();
    private volatile SeerQuerySession session;
    private volatile Boolean navigationSent;
    public SeerTcpAdapter(DeviceEndpoints endpoints) { this.endpoints = endpoints; }
    @Override public String protocolType() { return "tcp"; }
    @Override protected void doConnect(AdapterContext ctx) throws Exception {
        session = new SeerQuerySession(SeerConnectionSettings.endpoint(ctx.application(),endpoints,false));
        session.connect();
    }
    @Override protected void doDisconnect() { SeerQuerySession active = session; session = null; if (active != null) active.close(); }
    @Override public boolean isConnected() { SeerQuerySession active = session; return active != null && active.connected(); }
    @Override public boolean heartbeat() { return isConnected(); }
    @Override public Object read(ProtocolPointEntity point) {
        if(SeerPointCatalog.NAV_SENT.equals(point.getField_name())) return navigationSent;
        int api = api(point);
        try { requireConnected(); return value(point,session.query(api)); }
        catch (Exception ex) { doDisconnect(); throw ProtocolException.io(point.getField_name(), ex); }
    }
    /** Every poll queries each status packet once, regardless of the number of scalar points. */
    @Override public Map<String,Object> readAll(List<ProtocolPointEntity> points) {
        Map<Integer,Map<String,Object>> packets=new LinkedHashMap<>();
        Map<String,Object> result=new LinkedHashMap<>();
        try {
            requireConnected();
            for(var point:points) {
                if(!"response".equals(point.getDirection())) continue;
                if(SeerPointCatalog.NAV_SENT.equals(point.getField_name())) { result.put(point.getField_name(),navigationSent); continue; }
                int api=api(point);
                if(!packets.containsKey(api)) packets.put(api,session.query(api));
                result.put(point.getField_name(),value(point,packets.get(api)));
            }
            return result;
        } catch(Exception ex) { doDisconnect(); throw ProtocolException.io("仙工状态",ex); }
    }
    private int api(ProtocolPointEntity point) {
        Integer legacy=FIELDS.get(point.getField_name());
        var field=SeerPointCatalog.field(point.getField_name());
        String address=legacy!=null?legacy.toString():field!=null?field.address():null;
        if(address==null || !address.equals(point.getAddress()) || !"response".equals(point.getDirection()))
            throw new ProtocolException(4223,"仙工只读点位不在已发布合同中");
        return legacy!=null?legacy:field.api();
    }
    private Object value(ProtocolPointEntity point,Map<String,Object> packet) throws Exception {
        var field=SeerPointCatalog.field(point.getField_name());
        if(field==null)return json.writeValueAsString(packet);
        Object raw=((Map<?,?>)packet.get("payload")).get(field.key());
        if(raw==null)return null;
        if(field.type().equals("STRING") && !(raw instanceof String)) raw=json.writeValueAsString(raw);
        // Optional firmware fields can be absent or have an unsupported shape; never fabricate a value.
        try { return com.cayleywcs.protocol.contract.PointContractValidator.value(protocolType(),point,raw,4225); }
        catch(com.cayleywcs.common.exception.WcsException ex) { return null; }
    }
    @Override public void write(ProtocolPointEntity point, Object value) {
        throw new ProtocolException(4223, "仙工状态点位只读；导航须通过导航至站点专用操作下发");
    }
    public void assertNavigationIdle() {
        try {
            requireConnected();
            var location=(Map<?,?>)session.query(1004).get("payload");
            var speed=(Map<?,?>)session.query(1005).get("payload");
            var nav=(Map<?,?>)session.query(1020).get("payload");
            if(!(location.get("confidence") instanceof Number confidence) || confidence.doubleValue()<=0 || confidence.doubleValue()>1)
                throw new com.cayleywcs.common.exception.WcsException(409,"车辆定位置信度未知或无效，禁止下发导航");
            int status=((Number)nav.get("task_status")).intValue();
            if(!Set.of(0,4,5,6).contains(status) || !Boolean.TRUE.equals(speed.get("is_stop"))
                    || Math.abs(((Number)speed.get("vx")).doubleValue())>0.001
                    || Math.abs(((Number)speed.get("vy")).doubleValue())>0.001
                    || Math.abs(((Number)speed.get("w")).doubleValue())>0.001)
                throw new com.cayleywcs.common.exception.WcsException(409,"车辆正在执行任务或尚未停稳，禁止覆盖导航");
        } catch(java.io.IOException ex) { doDisconnect();throw ProtocolException.io("导航前状态确认",ex); }
    }
    public SeerNavigationSession.Result navigate(String station) {
        requireConnected();
        var result=SeerNavigationSession.send(SeerConnectionSettings.endpoint(context.application(),endpoints,true),station);
        navigationSent=result.status().equals("unknown")?null:result.sent();
        return result;
    }
}
