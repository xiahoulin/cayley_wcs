package com.cayleywcs.support;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.auth.JwtUser;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Explicit test migration to complete published profiles; it never synthesizes missing business fields or auto-grants links. */
public final class PublishedDeviceFixtures {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private PublishedDeviceFixtures() { }
    public static Map<String,Object> stackerPayload() {
        return Map.of("cmd_TaskType",1,"cmd_TakeCoor_Row",0,"cmd_TakeCoor_Column",0,"cmd_TakeCoor_Floor",0,
                "cmd_PutCoor_Row",1,"cmd_PutCoor_Column",1,"cmd_PutCoor_Floor",1,"cmd_PortNum",1);
    }
    public static ApplicationEntity ensure(ProtocolService protocols,ApplicationMapper apps,long appId,String type) {
        SecurityContext previous=SecurityContextHolder.getContext();
        try {
            ApplicationEntity app=apps.selectById(appId);long owner=app==null||app.getTenant_id()==null?1:app.getTenant_id();
            authenticate(owner);
            boolean fresh=app==null;
            if(fresh) {
                app=new ApplicationEntity();app.setId(appId);app.setApp_code("FIXTURE_APP_"+appId);app.setApp_name("测试设备 "+appId);app.setTenant_id(owner);app.setCreator("fixture");app.setCreate_time(LocalDateTime.now());app.setLast_update_time(LocalDateTime.now());
                app.setApp_key("fixture_"+appId+"_"+UUID.randomUUID());app.setApp_secret(UUID.randomUUID().toString());app.setIs_valid(true);app.setEnabled(true);app.setDevice_type(type);app.setDirection("none".equals(type)?"upstream":"downstream");app.setConn_params(Map.of());apps.insert(app);
            }
            String code="none".equals(type) && appId==4?"HTTP_DEVICE_CONTRACT":"FIXTURE_"+type.toUpperCase(java.util.Locale.ROOT)+"_"+owner+"_"+appId;
            ProtocolEntity profile=protocols.listAll().stream().filter(p->code.equals(p.getProtocol_code())).findFirst().orElse(null);
            if(profile==null) {
                // Copy only the explicit, audited simulator definitions from the seed owner.
                authenticate(1);
                String sourceCode=switch(type){case "none"->"HTTP_DEVICE_CONTRACT";case "stacker","palletizer"->"PALLET_SIM_OPCUA";case "conveyor"->"CONVEYOR_SIM";case "lift"->"LIFT_SIM";case "auto_door"->"DOOR_SIM";default->throw new IllegalArgumentException("未知测试设备类型: "+type);};
                ProtocolEntity source=protocols.listAll().stream().filter(p->sourceCode.equals(p.getProtocol_code())).findFirst().orElseThrow();
                List<ProtocolPointEntity> points=protocols.listPoints(source.getId()).stream().map(p->JSON.convertValue(p,ProtocolPointEntity.class)).toList();
                authenticate(owner);
                profile=new ProtocolEntity();profile.setApp_id(appId);profile.setProtocol_code(code);profile.setProtocol_name("已发布测试点位协议");profile.setProtocol_type("none".equals(type)?"http":"sim");profile.setStatus("enabled");profile.setData_format(Map.of());profile.setTransport_template(Map.of());profile=protocols.create(profile);
                for(ProtocolPointEntity point:points) {point.setId(null);point.setProtocol_id(profile.getId());point.setAddress("none".equals(type)?"payload."+point.getField_name():point.getField_name());protocols.createPoint(point);}
                profile=protocols.getById(profile.getId());
            }
            if(!"published".equals(profile.getLifecycle_state()))profile=protocols.publish(profile.getId(),profile.getRow_version());
            app.setProtocol_id(profile.getId());app.setProtocol_revision_id(profile.getPublished_revision_id());app.setDevice_type(type);app.setDirection("none".equals(type)?"upstream":"downstream");
            app.setEnabled(true);app.setIs_valid(true);app.setStatus("idle");app.setConfiguration_state("active");if(app.getConfiguration_epoch()==null)app.setConfiguration_epoch(0L);
            if(app.getApp_key()==null||app.getApp_key().isBlank())app.setApp_key("fixture_"+appId+"_"+UUID.randomUUID());
            if(app.getApp_secret()==null||app.getApp_secret().isBlank())app.setApp_secret(UUID.randomUUID().toString());
            if(app.getConn_params()==null)app.setConn_params(Map.of());if(app.getHeartbeat_interval_ms()==null)app.setHeartbeat_interval_ms(60000L);if(app.getMax_retry()==null)app.setMax_retry(1L);
            apps.updateById(app);
            apps.update(null,new UpdateWrapper<ApplicationEntity>().eq("\"id\"",appId).set("\"configuration_state\"","active").set("\"configuration_epoch\"",app.getConfiguration_epoch()).set("\"work_mode\"","normal").set("\"desired_state\"","closed"));
            return apps.selectById(appId);
        } finally {SecurityContextHolder.setContext(previous);}
    }
    private static void authenticate(long owner) {
        SecurityContext context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(new JwtUser(1L,"fixture_admin","fixture_admin","admin",owner,1L),null,List.of()));SecurityContextHolder.setContext(context);
    }
}
