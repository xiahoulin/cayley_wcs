package com.cayleywcs.dashboard.impl;

import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.DeviceStateService;
import com.cayleywcs.dashboard.DashboardService;
import com.cayleywcs.system.CurrentUserProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardServiceImpl implements DashboardService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private final ApplicationService applications;
    private final DeviceStateService states;
    private final ConnectionManager connections;
    private final CurrentUserProvider users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    public DashboardServiceImpl(ApplicationService applications, DeviceStateService states, ConnectionManager connections, CurrentUserProvider users, JdbcTemplate jdbc) {
        this.applications=applications;this.states=states;this.connections=connections;this.users=users;this.jdbc=jdbc;
    }
    @Override public List<Map<String,Object>> devices() {
        var user=users.currentUser();
        Map<Long,List<String>> selections=new HashMap<>();
        jdbc.query("select app_id,fields from wcs_dashboard_point_selection where tenant_id=? and user_id=?",rs->{
            selections.put(rs.getLong(1),decode(rs.getString(2)));
        },user.tenantId(),user.userId());
        List<Map<String,Object>> result=new ArrayList<>();
        for(var app:applications.listAll()) {
            if(connections.cachedSnapshot(app.getId())==null)continue;
            Map<String,Object> state=states.state(app.getId());
            var points=points(state);
            Set<String> available=new HashSet<>();points.forEach(p->available.add(String.valueOf(p.get("field"))));
            List<String> selected=selections.get(app.getId());
            state.put("selectionConfigured",selected!=null);
            state.put("selectedFields",selected==null?defaults(app.getDevice_type(),points):selected.stream().filter(available::contains).toList());
            result.add(state);
        }
        return result;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> points(Map<String,Object> state) {
        return (List<Map<String,Object>>)state.getOrDefault("points",List.of());
    }
    private static List<String> defaults(String deviceType,List<Map<String,Object>> points) {
        List<String> preferred="agv".equals(deviceType)
                ?List.of("loc_current_station","nav_task_status","speed_vx","speed_vy","speed_w","nav_target_id","nav_target_dist","loc_confidence")
                :List.of("PLC_Heart","status_Mode","status_Task","status_ErrorCode","status_CurrentColumnNum","status_CurrentFloorNum","status_Speed_Walk","status_Speed_Lift");
        Set<String> readable=new HashSet<>();points.stream().filter(p->Boolean.TRUE.equals(p.get("readable"))).forEach(p->readable.add(String.valueOf(p.get("field"))));
        List<String> selected=preferred.stream().filter(readable::contains).toList();
        return selected.isEmpty()?points.stream().filter(p->Boolean.TRUE.equals(p.get("readable")) && "business".equals(p.get("scope"))).map(p->String.valueOf(p.get("field"))).limit(8).toList():selected;
    }
    private List<String> decode(String value) {
        try{return json.readValue(value,new TypeReference<List<String>>(){});}
        catch(java.io.IOException ex){throw new IllegalStateException("看板点位配置损坏",ex);}
    }
    @Override @Transactional public void saveSelection(Selection selection) {
        lifecycle.references();
        if(selection==null || selection.appId()==null || selection.fields()==null || selection.fields().size()>512
                || selection.fields().stream().anyMatch(Objects::isNull) || new HashSet<>(selection.fields()).size()!=selection.fields().size())
            throw new WcsException(4223,"看板点位选择无效");
        var app=applications.getById(selection.appId());var user=users.currentUser();
        if(jdbc.queryForList("select id from wcs_application where id=? and tenant_id=? and is_valid=true for update",app.getId(),user.tenantId()).isEmpty())
            throw new WcsException(404,"应用不存在或不可访问");
        var state=states.state(app.getId());
        if(!Objects.equals(selection.revisionId(),state.get("revisionId")))throw new WcsException(409,"协议点位已更新，请重新打开看板展示编辑");
        Set<String> allowed=new HashSet<>();points(state).forEach(p->allowed.add(String.valueOf(p.get("field"))));
        if(!allowed.containsAll(selection.fields()))throw new WcsException(4223,"选择中包含未定义的应用点位");
        try {
            String fields=json.writeValueAsString(selection.fields());
            if(jdbc.update("update wcs_dashboard_point_selection set fields=? where tenant_id=? and user_id=? and app_id=?",fields,user.tenantId(),user.userId(),app.getId())==0)
                jdbc.update("insert into wcs_dashboard_point_selection(tenant_id,user_id,app_id,fields) values (?,?,?,?)",user.tenantId(),user.userId(),app.getId(),fields);
        }catch(java.io.IOException ex){throw new IllegalStateException(ex);}
    }
}
