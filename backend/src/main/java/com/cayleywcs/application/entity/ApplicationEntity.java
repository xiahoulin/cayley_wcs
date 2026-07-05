package com.cayleywcs.application.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.util.Map;

/**
 * 应用信息表（需求 3）：可对接的应用/设备。app_key 用于双向鉴权，protocol_id 关联协议表。
 * conn_params 为 JSON（host/port/endpoint/unitId 等连接参数）。
 *
 * 「专用接口独占」列（work_mode/dispatch_strategy/failure_policy/task_stale_seconds/desired_state）标注
 * {@code updateStrategy=NEVER}（对抗审查 P2）：普通 update() 全行 updateById 永不写这些列——它们只由
 * updateWorkMode/updateDesiredState 等单列 UpdateWrapper 写。否则 update() 读快照再全行写会把并发 open/close/
 * pause 刚落的值静默覆盖（wcs_application 无 @Version）。UpdateWrapper.set 是显式 SQL，不受本策略影响。
 */
@TableName(value = "\"wcs_application\"", autoResultMap = true)
public class ApplicationEntity extends BaseEntity {
    private String app_code;
    private String app_name;
    private String app_key;
    private String app_secret;
    private Long protocol_id;
    private String direction;
    private String device_type;
    /** 工作模式（M10）：normal 正常 / paused 暂停派工（在飞跑完）/ maintenance 维护（M11 完整语义）。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String work_mode;
    /** 派工策略（M10）：priority（默认）/ fifo，只影响 pending 排序（在飞优先是正确性约束）。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String dispatch_strategy;
    /** 失败处理策略（M11）：manual 转人工（默认）/ auto_retry 退避自动重派。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String failure_policy;
    /** 在飞任务停滞超时秒数（M11）：last_update_time 停滞超此值判 failed+报警；0=关闭。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long task_stale_seconds;
    /** 连接期望态（M12，用户意图）：open 自动恢复/维持连接 / closed 不恢复（默认）。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String desired_state;
    /** 最低签名版本（M13）：1=兼容旧签名 / 2=强制 body 纳签（防坐标/任务类型篡改）。 */
    private Long min_sign_version;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> conn_params;

    private Long max_retry;
    private Long heartbeat_interval_ms;
    private Boolean enabled;
    private String status;
    private String remark;

    public String getApp_code() {
        return app_code;
    }

    public void setApp_code(String app_code) {
        this.app_code = app_code;
    }

    public String getApp_name() {
        return app_name;
    }

    public void setApp_name(String app_name) {
        this.app_name = app_name;
    }

    public String getApp_key() {
        return app_key;
    }

    public void setApp_key(String app_key) {
        this.app_key = app_key;
    }

    public String getApp_secret() {
        return app_secret;
    }

    public void setApp_secret(String app_secret) {
        this.app_secret = app_secret;
    }

    public Long getProtocol_id() {
        return protocol_id;
    }

    public void setProtocol_id(Long protocol_id) {
        this.protocol_id = protocol_id;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getDevice_type() {
        return device_type;
    }

    public void setDevice_type(String device_type) {
        this.device_type = device_type;
    }

    public String getWork_mode() {
        return work_mode;
    }

    public void setWork_mode(String work_mode) {
        this.work_mode = work_mode;
    }

    public String getDispatch_strategy() {
        return dispatch_strategy;
    }

    public void setDispatch_strategy(String dispatch_strategy) {
        this.dispatch_strategy = dispatch_strategy;
    }

    public String getFailure_policy() {
        return failure_policy;
    }

    public void setFailure_policy(String failure_policy) {
        this.failure_policy = failure_policy;
    }

    public Long getTask_stale_seconds() {
        return task_stale_seconds;
    }

    public void setTask_stale_seconds(Long task_stale_seconds) {
        this.task_stale_seconds = task_stale_seconds;
    }

    public String getDesired_state() {
        return desired_state;
    }

    public void setDesired_state(String desired_state) {
        this.desired_state = desired_state;
    }

    public Long getMin_sign_version() {
        return min_sign_version;
    }

    public void setMin_sign_version(Long min_sign_version) {
        this.min_sign_version = min_sign_version;
    }

    public Map<String, Object> getConn_params() {
        return conn_params;
    }

    public void setConn_params(Map<String, Object> conn_params) {
        this.conn_params = conn_params;
    }

    public Long getMax_retry() {
        return max_retry;
    }

    public void setMax_retry(Long max_retry) {
        this.max_retry = max_retry;
    }

    public Long getHeartbeat_interval_ms() {
        return heartbeat_interval_ms;
    }

    public void setHeartbeat_interval_ms(Long heartbeat_interval_ms) {
        this.heartbeat_interval_ms = heartbeat_interval_ms;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
