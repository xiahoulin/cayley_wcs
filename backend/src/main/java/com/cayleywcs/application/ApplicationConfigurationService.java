package com.cayleywcs.application;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.binding.mapper.AppBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.connection.DeviceExecutionCoordinator;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.task.TaskStatus;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApplicationConfigurationService {
    private final DeviceExecutionCoordinator coordinator;
    private final ConnectionManager connections;
    private final JdbcTemplate jdbc;
    private final ProtocolContractService contracts;
    private final AppBindingMapper bindings;
    private final ApplicationMapper apps;
    private final TransactionTemplate transaction;
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;

    public ApplicationConfigurationService(DeviceExecutionCoordinator coordinator,@Lazy ConnectionManager connections,
            JdbcTemplate jdbc,ProtocolContractService contracts,AppBindingMapper bindings,ApplicationMapper apps,
            PlatformTransactionManager manager) {
        this.coordinator=coordinator; this.connections=connections; this.jdbc=jdbc; this.contracts=contracts;
        this.bindings=bindings; this.apps=apps; transaction=new TransactionTemplate(manager);
    }

    public <T> T update(Long appId,Supplier<T> update) {
        return coordinator.exclusive(appId,()->transaction.execute(status->{ lifecycle.references(); return update.get(); }));
    }

    public void reopenAfterCommit(Long appId) {
        jdbc.update("update wcs_application set desired_state='open' where id=?",appId);
        com.cayleywcs.common.support.ResourceLifecycleGuard.afterCommit(()->{
            try {connections.open(appId);}
            catch(RuntimeException ex) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("AGV 配置已保存，重连尚未成功 appId={}: {}",appId,ex.getMessage());}
        });
    }

    public void prepareChange(ApplicationEntity old,ApplicationEntity next) {
        for(var link:jdbc.queryForList("select gateway_id,sensor_kind from wcs_sensor_application where app_id=?",old.getId())) {
            var params=next.getConn_params();
            if(!Objects.equals(link.get("sensor_kind"),next.getDevice_type()) || params==null
                    || !String.valueOf(link.get("gateway_id")).equals(String.valueOf(params.get("gatewayId")))
                    || !Objects.equals(link.get("sensor_kind"),params.get("sensorKind")))
                throw new WcsException(ErrorCode.CONFLICT,"传感器应用的网关与类型关联不可修改，请使用采集档案管理");
        }
        boolean sameSelection=Objects.equals(old.getProtocol_id(),next.getProtocol_id()) && Objects.equals(old.getProtocol_revision_id(),next.getProtocol_revision_id());
        if(sameSelection && old.getProtocol_revision_id()==null) return; // Registered identity; protocol activation is a separate explicit step.
        var revision=contracts.resolve(next);
        next.setProtocol_revision_id(revision.id());
        boolean changed=!Objects.equals(old.getProtocol_id(),next.getProtocol_id())
                || !Objects.equals(old.getProtocol_revision_id(),next.getProtocol_revision_id())
                || !Objects.equals(old.getDevice_type(),next.getDevice_type())
                || !Objects.equals(old.getAdapter_type(),next.getAdapter_type())
                || !Objects.equals(old.getConn_params(),next.getConn_params());
        if (!changed) return;
        assertIdle(old);
        var rows=bindings.selectList(new QueryWrapper<com.cayleywcs.binding.entity.AppBindingEntity>()
                .eq("\"is_valid\"",true).eq("\"enabled\"",true)
                .and(q->q.eq("\"upstream_app_id\"",old.getId()).or().eq("\"downstream_app_id\"",old.getId())));
        for (var row:rows) {
            // 尚未核对的旧绑定没有授权效力；选定双方版本之后由管理端显式重新授权。
            if (row.getContract_fingerprint()==null || row.getUpstream_revision_id()==null || row.getDownstream_revision_id()==null) continue;
            ApplicationEntity up=Objects.equals(row.getUpstream_app_id(),old.getId())?next:apps.selectById(row.getUpstream_app_id());
            ApplicationEntity down=Objects.equals(row.getDownstream_app_id(),old.getId())?next:apps.selectById(row.getDownstream_app_id());
            var match=contracts.requireMatch(up,down);
            row.setUpstream_revision_id(match.upstreamSnapshotId()); row.setDownstream_revision_id(match.downstreamSnapshotId());
            row.setContract_fingerprint(match.fingerprint());
            if (bindings.updateById(row)!=1) throw new WcsException(ErrorCode.CONFLICT,"绑定同时被修改，切换已取消");
        }
        jdbc.update("update wcs_application set configuration_state='switching',configuration_epoch=configuration_epoch+1 where id=?",old.getId());
        if (connections.isOpen(old.getId())) connections.close(old.getId());
        // 明确保存所选版本；保持关闭，由连接恢复或显式open验证新配置。
        jdbc.update("update wcs_application set configuration_state='active' where id=?",old.getId());
        next.setConfiguration_epoch(old.getConfiguration_epoch()==null?1:old.getConfiguration_epoch()+1);
    }

    public void assertIdle(ApplicationEntity app) {
        if(jdbc.queryForObject("select count(*) from wcs_agv_navigation_command where app_id=? and status in ('sending','accepted','unknown') and acknowledged_at is null",Long.class,app.getId())>0)
            throw new WcsException(ErrorCode.CONFLICT,"存在待核对的 AGV 导航指令，请确认车辆状态后解除下发锁定");
        Long active=jdbc.queryForObject("select count(*) from wcs_task where (app_id=? or caller_app_id=?) and is_valid=true and status not in ('completed','failed','cancelled')",Long.class,app.getId(),app.getId());
        Long leases=jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where app_id=?",Long.class,app.getId());
        if (active>0 || leases>0) throw new WcsException(ErrorCode.CONFLICT,"设备存在活动任务或未清理设备任务号，禁止切换配置");
        if (jdbc.queryForObject("select count(*) from wcs_task_chain where upstream_app_id=? and is_valid=true and status in ('pending','running')",Long.class,app.getId())>0)
            throw new WcsException(ErrorCode.CONFLICT,"应用存在未完成任务链，禁止切换配置");
        for (var chain:jdbc.queryForList("select segments from wcs_task_chain where is_valid=true and status in ('pending','running')")) {
            String segments=String.valueOf(chain.get("segments"));
            try {
                var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(segments);
                for (var segment:tree) {
                    if (segment.path("appId").asLong(-1)==app.getId()) throw new WcsException(ErrorCode.CONFLICT,"未完成任务链仍引用设备");
                    for (var id:segment.path("candidateAppIds")) if (id.asLong()==app.getId()) throw new WcsException(ErrorCode.CONFLICT,"未完成任务链候选仍引用设备");
                }
            } catch (WcsException ex) {throw ex;} catch (Exception ex) {throw new WcsException(ErrorCode.CONFLICT,"任务链引用无法确认");}
        }
        if ("agv".equals(app.getDevice_type()) || com.cayleywcs.telemetry.profile.SensorPointCatalog.isSensor(app.getDevice_type())) return;
        if (connections.isOpen(app.getId())) {
            var snapshot=connections.snapshot(app.getId());
            if (!"RUNNING".equals(snapshot.state())) throw new WcsException(ErrorCode.CONFLICT,"设备状态无法确认，禁止切换");
            if (!"auto_door".equals(app.getDevice_type())) {
                Object state=connections.readPoint(app.getId(),"status_Task");
                if (!(state instanceof Number n) || n.longValue()!=0) throw new WcsException(ErrorCode.CONFLICT,"设备尚未空闲");
            } else {
                Object state=connections.readPoint(app.getId(),"status_DoorState");
                if (!(state instanceof Number n) || (n.longValue()!=0 && n.longValue()!=1)) throw new WcsException(ErrorCode.CONFLICT,"自动门仍在动作");
            }
        } else if ("open".equals(app.getDesired_state())) {
            throw new WcsException(ErrorCode.CONFLICT,"设备连接未恢复，不能确认物理空闲");
        }
    }

}
