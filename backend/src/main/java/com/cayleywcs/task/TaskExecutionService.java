package com.cayleywcs.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.binding.BindingService;
import com.cayleywcs.binding.entity.AppBindingEntity;
import com.cayleywcs.binding.mapper.AppBindingMapper;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.connection.DeviceExecutionCoordinator;
import com.cayleywcs.connection.ConnectionManager;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将来源、点位版本及原生任务号固定在任务上，设备写入之前再次验证。 */
@Service
public class TaskExecutionService {
    private final ApplicationMapper applications;
    private final AppBindingMapper bindingRows;
    private final BindingService bindings;
    private final ProtocolContractService contracts;
    private final TaskMapper tasks;
    private final JdbcTemplate jdbc;
    private final ConnectionManager connections;
    private final DeviceExecutionCoordinator coordinator;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public TaskExecutionService(ApplicationMapper applications,AppBindingMapper bindingRows,BindingService bindings,
            ProtocolContractService contracts,TaskMapper tasks,JdbcTemplate jdbc,@Lazy ConnectionManager connections,
            DeviceExecutionCoordinator coordinator,org.springframework.transaction.PlatformTransactionManager manager) {
        this.applications=applications; this.bindingRows=bindingRows; this.bindings=bindings;
        this.contracts=contracts; this.tasks=tasks; this.jdbc=jdbc; this.connections=connections;
        this.coordinator=coordinator; this.transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    public Map<String,Object> normalize(Long appId, Map<String,Object> payload) {
        ApplicationEntity app = requiredApp(appId);
        assertDispatchEnabled(app);
        return contracts.validatePayload(app, payload);
    }

    private static void assertDispatchEnabled(ApplicationEntity app) {
        if (com.cayleywcs.telemetry.profile.SensorPointCatalog.isSensor(app.getDevice_type())) throw new WcsException(4223,"传感器应用仅采集状态，不接受设备任务");
        if ("agv".equals(app.getDevice_type())) throw new WcsException(4223, "AGV 当前仅开放只读状态，导航闭环尚未启用");
    }

    public Long revisionId(Long appId) { return contracts.resolve(requiredApp(appId)).id(); }

    public void assertCandidate(Long caller,Long appId) {
        ApplicationEntity app=requiredApp(appId);
        assertDispatchEnabled(app);
        if (caller!=null && caller>0) bindings.assertAllowed(caller,appId,app.getTenant_id());
    }

    public <T> T exclusiveTransaction(Long appId,java.util.function.Supplier<T> action) {
        return coordinator.exclusive(appId,()->transaction.execute(status->action.get()));
    }

    public void cleanupUnstarted(TaskEntity task) {
        Long held=jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where task_id=?",Long.class,task.getId());
        if (held==0) return;
        if (TaskStatus.PENDING.equals(task.getStatus()) && HandshakeStep.CREATED.name().equals(task.getHandshake_step())
                && task.getDispatch_time()==null && !Boolean.TRUE.equals(task.getPhysical_started())) {
            jdbc.update("delete from wcs_device_task_number_lease where task_id=?",task.getId());
            return;
        }
        ApplicationEntity app=requiredApp(task.getApp_id());
        if (!connections.matchesConfiguration(app)) throw new WcsException(ErrorCode.CONFLICT,"连接不可确认，不能取消持有设备参数的任务");
        Object state=connections.readPoint(app.getId(),"status_Task");
        if (!(state instanceof Number n) || n.longValue()!=0) throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL,"设备已受理任务，不能取消");
        for (var point:contracts.resolve(app).points()) {
            if (point.getField_name().startsWith("cmd_") && "request".equals(point.getDirection())
                    && !"cmd_ResetFault".equals(point.getField_name()))
                connections.writePoint(app.getId(),point.getField_name(),0);
        }
        jdbc.update("delete from wcs_device_task_number_lease where task_id=?",task.getId());
    }

    public void assertMaintenanceIdle(ApplicationEntity app) {
        Long active=jdbc.queryForObject("select count(*) from wcs_task where app_id=? and is_valid=true and status not in ('completed','failed','cancelled')",Long.class,app.getId());
        Long leases=jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where app_id=?",Long.class,app.getId());
        if (active>0 || leases>0) throw new WcsException(ErrorCode.CONFLICT,"设备存在活动任务或未清理任务号，禁止运维写入");
        String stateField="auto_door".equals(app.getDevice_type())?"status_DoorState":"status_Task";
        Object state=connections.readPoint(app.getId(),stateField);
        if (!(state instanceof Number n) || ("auto_door".equals(app.getDevice_type())?(n.longValue()!=0 && n.longValue()!=1):n.longValue()!=0))
            throw new WcsException(ErrorCode.CONFLICT,"设备尚未物理空闲，禁止运维写入");
    }

    public void assertFaultResetAllowed(ApplicationEntity app) {
        Long active=jdbc.queryForObject("select count(*) from wcs_task where app_id=? and is_valid=true and status in ('dispatched','executing')",Long.class,app.getId());
        Long owned=jdbc.queryForObject("select count(*) from wcs_device_task_number_lease l join wcs_task t on t.id=l.task_id where l.app_id=? and t.status not in ('failed','cancelled','completed')",Long.class,app.getId());
        if (active>0 || owned>0) throw new WcsException(ErrorCode.CONFLICT,"任务仍在执行或确认阶段，禁止故障复位");
        if (!hasFault(connections.readPoint(app.getId(),"status_ErrorCode"))) assertMaintenanceIdle(app);
    }

    @Transactional
    public void confirmFaultReset(ApplicationEntity app) {
        String field="auto_door".equals(app.getDevice_type())?"status_DoorState":"status_Task";
        Object state=connections.readPoint(app.getId(),field);
        boolean idle=state instanceof Number n && ("auto_door".equals(app.getDevice_type())?(n.longValue()==0 || n.longValue()==1):n.longValue()==0);
        if (!idle || hasFault(connections.readPoint(app.getId(),"status_ErrorCode"))) return; // 复位尚未确认，仍占用设备号。
        jdbc.update("delete from wcs_device_task_number_lease where app_id=? and task_id in (select id from wcs_task where app_id=? and status in ('failed','cancelled'))",app.getId(),app.getId());
        jdbc.update("update wcs_task set physical_started=false,held_reason='设备复位已确认空闲' where app_id=? and status='failed'",app.getId());
    }

    private static boolean hasFault(Object value) {
        if (value instanceof Number n) return n.longValue()!=0;
        if (value instanceof Iterable<?> values) { for (Object item:values) if (hasFault(item)) return true; return false; }
        throw new WcsException(4225,"故障反馈格式无法确认");
    }

    public void initialize(TaskEntity task,Long caller) {
        ApplicationEntity app=requiredApp(task.getApp_id());
        assertDispatchEnabled(app);
        if ((caller==null || caller==0) && !Objects.equals(task.getTenant_id(),app.getTenant_id()))
            throw new WcsException(ErrorCode.FORBIDDEN,"管理任务与设备不属于同一固定归属");
        Map<String,Object> original=task.getPayload() == null ? Map.of() : new LinkedHashMap<>(task.getPayload());
        var snapshot=contracts.resolve(app);
        task.setPayload(contracts.validatePayload(app,task.getPayload()));
        task.setOriginal_request(original);
        task.setCaller_app_id(caller);
        task.setTenant_id(app.getTenant_id());
        task.setProtocol_revision_id(snapshot.id());
        task.setExecution_epoch(app.getConfiguration_epoch());
        task.setHeld_reason("");
        if (caller != null && caller > 0) {
            bindings.assertAllowed(caller, app.getId(), app.getTenant_id());
            AppBindingEntity row=bindingRows.selectOne(new QueryWrapper<AppBindingEntity>()
                    .eq("\"upstream_app_id\"",caller).eq("\"downstream_app_id\"",app.getId())
                    .eq("\"tenant_id\"",app.getTenant_id()).eq("\"is_valid\"",true).eq("\"enabled\"",true));
            task.setBinding_id(row.getId()); task.setContract_fingerprint(row.getContract_fingerprint());
        } else task.setContract_fingerprint(snapshot.fingerprint());
        if (task.getPayload().get("cmd_TaskType") instanceof Number type) task.setTask_type(type.toString());
    }

    @Transactional
    public boolean prepare(TaskEntity task) {
        try {
            ApplicationEntity app=requiredApp(task.getApp_id());
            assertVersion(task,app);
            if (!Boolean.TRUE.equals(task.getPhysical_started()) || TaskStatus.PENDING.equals(task.getStatus())) assertAuthority(task,app);
            if (task.getCaller_app_id() == null) throw new WcsException(ErrorCode.FORBIDDEN,"历史任务来源待核对");
            assignNativeNumber(task,app);
            task.setHeld_reason("");
            return true;
        } catch (WcsException ex) {
            task.setHeld_reason(ex.getMessage());
            return false;
        }
    }

    public void assertRetryAllowed(TaskEntity task) {
        ApplicationEntity app=requiredApp(task.getApp_id());
        assertVersion(task,app);
        assertAuthority(task,app);
    }

    public void assertCanStart(TaskEntity task) {
        ApplicationEntity app=requiredApp(task.getApp_id());
        assertVersion(task,app); assertAuthority(task,app);
        task.setPhysical_started(true); // 先记录执行意图；写入超时仍按可能已受理恢复。
    }

    public void assertWriteAllowed(TaskEntity task) {
        ApplicationEntity app=requiredApp(task.getApp_id());
        assertVersion(task,app);
        if (!Boolean.TRUE.equals(task.getPhysical_started()) || TaskStatus.PENDING.equals(task.getStatus())) assertAuthority(task,app);
        TaskEntity persisted=tasks.selectById(task.getId());
        if (persisted==null || TaskStatus.CANCELLED.equals(persisted.getStatus()))
            throw new WcsException(ErrorCode.TASK_STATE_ILLEGAL,"任务已取消，禁止设备写入");
    }

    private void assertAuthority(TaskEntity task,ApplicationEntity app) {
        if (task.getCaller_app_id() == null) throw new WcsException(ErrorCode.FORBIDDEN,"任务缺少可信来源");
        if (!Boolean.TRUE.equals(app.getEnabled())) throw new WcsException(ErrorCode.FORBIDDEN,"设备应用已停用");
        if (task.getCaller_app_id()>0) {
            bindings.assertAllowed(task.getCaller_app_id(),task.getApp_id(),task.getTenant_id());
            AppBindingEntity row=bindingRows.selectById(task.getBinding_id());
            if (row == null || !Objects.equals(row.getContract_fingerprint(),task.getContract_fingerprint()))
                throw new WcsException(ErrorCode.CONFLICT,"任务绑定版本已改变");
        }
    }

    private void assertVersion(TaskEntity task,ApplicationEntity app) {
        var snapshot=contracts.resolve(app);
        if (!"active".equals(app.getConfiguration_state()) || !Objects.equals(task.getProtocol_revision_id(),snapshot.id())
                || !Objects.equals(task.getExecution_epoch(),app.getConfiguration_epoch()))
            throw new WcsException(ErrorCode.CONFLICT,"任务点位版本失配，请恢复原版本或受控转换");
        if (!connections.matchesConfiguration(app)) throw new WcsException(ErrorCode.CONFLICT,"设备连接配置代次不匹配");
    }

    @Transactional
    public void assignNativeNumber(TaskEntity task,ApplicationEntity app) {
        var numberPoint=contracts.resolve(app).points().stream().filter(p->"cmd_TaskNum".equals(p.getField_name())).findFirst();
        if (numberPoint.isEmpty()) return;
        // 应用行锁保证首次计数行创建也不会并发撞唯一索引。
        jdbc.queryForObject("select id from wcs_application where id=? for update",Long.class,app.getId());
        if (jdbc.queryForObject("select count(*) from wcs_device_task_number where app_id=?",Long.class,app.getId())==0)
            jdbc.update("insert into wcs_device_task_number(app_id,next_number) values (?,1)",app.getId());
        Long lease=jdbc.query("select native_number from wcs_device_task_number_lease where task_id=?",rs->rs.next()?rs.getLong(1):null,task.getId());
        if (lease != null) { task.setNative_task_num(lease); return; }
        Object deviceState=connections.readPoint(app.getId(),"status_Task");
        if (!(deviceState instanceof Number state) || state.longValue()!=0)
            throw new WcsException(ErrorCode.CONFLICT,"设备尚未空闲，禁止新分配或回绕复用任务号");
        if (jdbc.queryForObject("select count(*) from wcs_device_task_number_lease where app_id=?",Long.class,app.getId())>0)
            throw new WcsException(ErrorCode.CONFLICT,"设备旧任务尚未完成确认，禁止分配新任务号");
        String type=com.cayleywcs.adapter.PointTypeRegistry.canonical(contracts.resolve(app).protocolType(),numberPoint.get().getData_type());
        long maximum=switch(type) {
            case "INT8" -> 127; case "UINT8" -> 255; case "INT16" -> 32767; case "UINT16" -> 65535;
            case "INT32" -> Integer.MAX_VALUE; case "UINT32" -> 4294967295L; case "INT64","UINT64" -> Long.MAX_VALUE;
            default -> throw new WcsException(4224,"原生任务号必须为整数类型");
        };
        var rules=numberPoint.get().getConstraints()==null?Map.<String,Object>of():numberPoint.get().getConstraints();
        if (rules.get("max")!=null) maximum=new java.math.BigDecimal(rules.get("max").toString()).min(java.math.BigDecimal.valueOf(maximum)).longValueExact();
        long minimum=rules.get("min")==null?1:Math.max(1,new java.math.BigDecimal(rules.get("min").toString()).longValueExact());
        if (maximum<minimum) throw new WcsException(4224,"原生任务号范围无可用值");
        long next=jdbc.queryForObject("select next_number from wcs_device_task_number where app_id=? for update",Long.class,app.getId());
        if (next>maximum || next<minimum) next=minimum;
        java.util.Set<Long> reserved=new java.util.HashSet<>();
        if (rules.get("reserved_values") instanceof java.util.Collection<?> values)
            for (Object value:values) reserved.add(new java.math.BigDecimal(value.toString()).longValueExact());
        int attempts=0;
        while (reserved.contains(next)) {
            if (++attempts>reserved.size()) throw new WcsException(4224,"原生任务号全为保留值");
            next=next==maximum?minimum:next+1;
        }
        jdbc.update("insert into wcs_device_task_number_lease(app_id,native_number,task_id) values (?,?,?)",app.getId(),next,task.getId());
        jdbc.update("update wcs_device_task_number set next_number=? where app_id=?",next==maximum?minimum:next+1,app.getId());
        jdbc.update("update wcs_task set native_task_num=? where id=?",next,task.getId());
        task.setNative_task_num(next);
    }

    public void captureResult(TaskEntity task) {
        if (task.getResult()!=null) return;
        ApplicationEntity app=requiredApp(task.getApp_id());
        Map<String,Object> response=connections.responsePayload(app.getId());
        Map<String,Object> normalized=contracts.validateResponse(app,response);
        Object feedback=response.get("status_TaskNum");
        if (task.getNative_task_num()!=null && (!(feedback instanceof Number n) || n.longValue()!=task.getNative_task_num()))
            throw new WcsException(4225,"设备完成反馈任务号不属于本任务");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("capturedAt",System.currentTimeMillis()); result.put("quality","good"); result.put("payload",normalized);
        task.setResult(result);
        Long before=task.getVersion();
        if (tasks.updateById(task)!=1) {task.setVersion(before); task.setResult(null); throw new WcsException(ErrorCode.CONFLICT,"保存最终结果时任务已变化");}
    }

    public void releaseIfCompleted(TaskEntity task) {
        if (!TaskStatus.COMPLETED.equals(task.getStatus())) return;
        jdbc.update("delete from wcs_device_task_number_lease where task_id=?",task.getId());
    }

    private ApplicationEntity requiredApp(Long id) {
        ApplicationEntity app=applications.selectById(id);
        if (app==null || !Boolean.TRUE.equals(app.getIs_valid())) throw new WcsException(ErrorCode.NOT_FOUND,"设备应用不存在");
        return app;
    }
}
