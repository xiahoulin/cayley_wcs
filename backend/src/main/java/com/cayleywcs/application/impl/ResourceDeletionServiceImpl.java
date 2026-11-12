package com.cayleywcs.application.impl;

import com.cayleywcs.application.ResourceDeletionService;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.application.security.AppKeyReplayCache;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.ResourceLifecycleGuard;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.DeviceExecutionCoordinator;
import com.cayleywcs.faultcode.FaultCodeResolver;
import com.cayleywcs.protocol.mapper.ProtocolMapper;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.telemetry.polling.SensorPollingService;
import com.cayleywcs.telemetry.storage.TelemetryBuffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResourceDeletionServiceImpl implements ResourceDeletionService {
    private final JdbcTemplate jdbc;
    private final ApplicationMapper apps;
    private final ProtocolMapper protocols;
    private final CurrentUserProvider users;
    private final ResourceLifecycleGuard guard;
    private final DeviceExecutionCoordinator coordinator;
    private final ObjectProvider<ConnectionManager> connections;
    private final ObjectProvider<SensorPollingService> polling;
    private final ObjectProvider<TelemetryBuffer> buffers;
    private final AppKeyReplayCache auth;
    private final FaultCodeResolver faults;
    private final ObjectMapper json = new ObjectMapper();

    public ResourceDeletionServiceImpl(JdbcTemplate jdbc, ApplicationMapper apps, ProtocolMapper protocols,
            CurrentUserProvider users, ResourceLifecycleGuard guard, DeviceExecutionCoordinator coordinator,
            ObjectProvider<ConnectionManager> connections, ObjectProvider<SensorPollingService> polling,
            ObjectProvider<TelemetryBuffer> buffers, AppKeyReplayCache auth, FaultCodeResolver faults) {
        this.jdbc=jdbc; this.apps=apps; this.protocols=protocols; this.users=users; this.guard=guard;
        this.coordinator=coordinator; this.connections=connections; this.polling=polling;
        this.buffers=buffers; this.auth=auth; this.faults=faults;
    }

    @Override public boolean deleteApplication(Long id) {
        if (id==null) throw new WcsException(400,"应用 ID 必填");
        return coordinator.exclusive(id, () -> guard.deletion(() -> deleteApplicationLocked(id)));
    }

    private boolean deleteApplicationLocked(Long id) {
        var app=apps.selectById(id);
        if (app==null || !Boolean.TRUE.equals(app.getIs_valid()) || !Objects.equals(app.getTenant_id(),tenant()))
            throw new WcsException(404,"应用不存在或不可访问");
        reject(count("select count(*) from wcs_task where app_id=? or caller_app_id=?",id,id)>0,
                "应用仍被任务引用（包含历史任务），不能删除");
        reject(count("select count(*) from wcs_app_binding where upstream_app_id=? or downstream_app_id=?",id,id)>0,
                "应用仍被授权记录引用（包含停用或撤销记录），不能删除");
        var owned=jdbc.queryForList("select id from wcs_protocol where app_id=? order by id",Long.class,id);
        var revisions=revisionIds(owned);
        assertChains(Set.of(id),new HashSet<>(revisions));
        assertVersionReferences(revisions);
        reject("open".equals(app.getDesired_state()) || connections.getObject().cachedSnapshot(id)!=null,
                "请先在连接监控关闭应用连接，再删除应用");
        reject(count("select count(*) from wcs_device_task_number_lease where app_id=?",id)>0,
                "应用仍有设备任务号占用，不能删除");
        for (var hook:jdbc.queryForList("select scope_app_ids from wcs_webhook"))
            reject(containsId(parse(hook.get("scope_app_ids")),id),"应用仍被 Webhook 范围引用，请先调整或删除该回调配置");
        List<Long> deliveries=new ArrayList<>();
        for (var row:jdbc.queryForList("select id,status,payload from wcs_webhook_delivery")) {
            var payload=parse(row.get("payload"));
            if (payload.path("appId").asLong(-1)==id || payload.path("upstreamAppId").asLong(-1)==id) {
                reject("pending".equals(row.get("status")),"应用仍有待投递回调，不能删除");
                deliveries.add(((Number)row.get("id")).longValue());
            }
        }
        var gatewayIds=jdbc.queryForList("select id from wcs_sensor_gateway where agv_app_id=?",Long.class,id);
        for (Long gateway:gatewayIds) {
            reject(count("select count(*) from wcs_sensor_application where gateway_id=? and app_id<>?",gateway,id)>0,
                    "采集网关仍被其他传感器应用引用，请先处理这些应用");
            reject(count("select count(*) from wcs_sensor_gateway where id=? and enabled=true",gateway)>0,
                    "请先停止采集网关，再删除应用");
        }
        var linkedGateways=jdbc.queryForList("select gateway_id from wcs_sensor_application where app_id=?",Long.class,id);
        for (Long gateway:linkedGateways) {
            reject(count("select count(*) from wcs_sensor_gateway where id=? and enabled=true",gateway)>0,
                    "请先停止共享采集网关，再删除传感器应用");
            reject(count("select count(*) from wcs_sensor_application s join wcs_application a on a.id=s.app_id where s.gateway_id=? and a.desired_state='open'",gateway)>0,
                    "共享采集网关仍在使用，请先关闭该网关下的传感器连接");
        }
        // Clear only this application's selection to break the ownership/publication FK cycle.
        jdbc.update("update wcs_application set protocol_id=null,protocol_revision_id=null where id=?",id);
        removeProtocols(owned);
        deleteIds("wcs_webhook_delivery",deliveries);
        jdbc.update("delete from wcs_sensor_application where app_id=?",id);
        for (Long gateway:gatewayIds) {
            jdbc.update("delete from wcs_telemetry_latest where gateway_id=?",gateway);
            jdbc.update("delete from wcs_telemetry_cycle where gateway_id=?",gateway);
            jdbc.update("delete from wcs_sensor_gateway where id=?",gateway);
        }
        for (String table:List.of("wcs_open_token","wcs_dashboard_point_selection","wcs_alarm",
                "wcs_connection_log","wcs_message_log","wcs_device_task_number"))
            jdbc.update("delete from "+table+" where app_id=?",id);
        jdbc.update("delete from wcs_contract_migration_audit where entity_type='application' and entity_id=?",id);
        int removed=jdbc.update("delete from wcs_application where id=?",id);
        Set<Long> evictGateways=new HashSet<>(gatewayIds); evictGateways.addAll(linkedGateways);
        ResourceLifecycleGuard.afterCommit(() -> {
            faults.invalidateProtocols(owned);
            for (Long gateway:evictGateways) { polling.getObject().stop(gateway); buffers.getObject().forget(gateway); }
            auth.evictApplication(app.getApp_key());
        });
        return removed==1;
    }

    @Override public boolean deleteProtocol(Long id) {
        return guard.deletion(() -> {
            var protocol=id==null?null:protocols.selectById(id);
            if (protocol==null || !Objects.equals(protocol.getTenant_id(),tenant()))
                throw new WcsException(404,"协议不存在或不可访问");
            reject(count("select count(*) from wcs_application where protocol_id=?",id)>0,
                    "协议仍被应用选用，请先切换应用协议，不能直接删除");
            var revisions=revisionIds(List.of(id));
            assertVersionReferences(revisions);
            assertChains(Set.of(),new HashSet<>(revisions));
            removeProtocols(List.of(id));
            ResourceLifecycleGuard.afterCommit(() -> faults.invalidateProtocols(List.of(id)));
            return true;
        });
    }

    private void assertVersionReferences(List<Long> revisions) {
        for(Long revision:revisions) reject(count("select count(*) from wcs_agv_navigation_command where protocol_revision_id=?",revision)>0,
                "导航下发记录仍引用该协议版本，禁止删除");
        for (Long revision:revisions) {
            reject(count("select count(*) from wcs_task where protocol_revision_id=?",revision)>0,
                    "协议发布版本仍被任务引用（包含历史任务），不能删除");
            reject(count("select count(*) from wcs_app_binding where upstream_revision_id=? or downstream_revision_id=?",revision,revision)>0,
                    "协议发布版本仍被授权记录引用，不能删除");
        }
    }

    private void assertChains(Set<Long> appIds,Set<Long> revisionIds) {
        for (var row:jdbc.queryForList("select upstream_app_id,segments,original_segments from wcs_task_chain")) {
            Object upstream=row.get("upstream_app_id");
            reject(upstream instanceof Number n && appIds.contains(n.longValue()),"应用仍被任务链引用，不能删除");
            for (String column:List.of("segments","original_segments"))
                reject(chainReference(parse(row.get(column)),appIds,revisionIds),"应用或协议版本仍被任务链引用（包含历史链），不能删除");
        }
    }

    private boolean chainReference(JsonNode node,Set<Long> apps,Set<Long> revisions) {
        if (node.isArray()) { for (var child:node) if(chainReference(child,apps,revisions)) return true; }
        if (node.isObject()) {
            if (apps.contains(node.path("appId").asLong(-1))) return true;
            for (var candidate:node.path("candidateAppIds")) if(apps.contains(candidate.asLong(-1))) return true;
            var pinned=node.path("pinnedRevisionIds");
            var entries=pinned.fields();
            while(entries.hasNext()) {
                var entry=entries.next();
                if (apps.stream().anyMatch(id->id.toString().equals(entry.getKey())) || revisions.contains(entry.getValue().asLong(-1))) return true;
            }
            // Original chain envelopes can contain a nested segments collection.
            if(node.has("segments") && chainReference(node.get("segments"),apps,revisions)) return true;
        }
        return false;
    }

    private JsonNode parse(Object value) {
        if(value==null || value.toString().isBlank()) return json.nullNode();
        try { return json.readTree(value.toString()); }
        catch(Exception ex) { throw new WcsException(409,"关联记录格式异常，无法确认删除安全性"); }
    }
    private boolean containsId(JsonNode array,Long id) { for(var item:array) if(item.asLong(-1)==id) return true;return false; }
    private Long tenant(){return users.currentUser().tenantId();}
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private static void reject(boolean condition,String message){if(condition)throw new WcsException(409,message);}
    private List<Long> revisionIds(List<Long> ids) {
        List<Long> result=new ArrayList<>();
        for(Long id:ids) result.addAll(jdbc.queryForList("select id from wcs_protocol_revision where protocol_id=?",Long.class,id));
        return result;
    }
    private void deleteIds(String table,List<Long> ids){for(Long id:ids)jdbc.update("delete from "+table+" where id=?",id);}
    private void removeProtocols(List<Long> ids) {
        for (Long id:ids) {
            jdbc.update("delete from wcs_contract_migration_audit where (entity_type='protocol' and entity_id=?) or (entity_type in ('point','protocol_point') and entity_id in (select id from wcs_protocol_point where protocol_id=?)) or (entity_type in ('revision','protocol_revision') and entity_id in (select id from wcs_protocol_revision where protocol_id=?))",id,id,id);
            jdbc.update("update wcs_protocol set published_revision_id=null where id=?",id);
            jdbc.update("delete from wcs_fault_code where protocol_id=?",id);
            jdbc.update("delete from wcs_protocol_point where protocol_id=?",id);
            jdbc.update("delete from wcs_protocol_revision where protocol_id=?",id);
            jdbc.update("delete from wcs_protocol where id=?",id);
        }
    }
}
