package com.cayleywcs.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.time.LocalDateTime;
import java.util.Map;

/** WCS 任务（wcs_task）。承载 WMS 下发 + 堆垛机三段握手执行态。 */
@TableName(value = "\"wcs_task\"", autoResultMap = true)
public class TaskEntity extends BaseEntity {
    private String task_no;
    private Long app_id;
    private String wms_ref;
    private String task_type;
    private Long take_row;
    private Long take_column;
    private Long take_floor;
    private Long put_row;
    private Long put_column;
    private Long put_floor;
    private Long port_num;
    private Long priority;
    private String status;
    private String handshake_step;
    private Long error_code;
    private Long caller_app_id;
    private Long binding_id;
    private Long protocol_revision_id;
    private Long execution_epoch;
    private Long native_task_num;
    private Boolean physical_started;
    private String contract_fingerprint;
    private String request_fingerprint;
    private String idempotency_scope;
    private String held_reason;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> result;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> original_request;

    public Long getCaller_app_id() { return caller_app_id; }
    public void setCaller_app_id(Long value) { caller_app_id = value; }
    public Long getBinding_id() { return binding_id; }
    public void setBinding_id(Long value) { binding_id = value; }
    public Long getProtocol_revision_id() { return protocol_revision_id; }
    public void setProtocol_revision_id(Long value) { protocol_revision_id = value; }
    public Long getExecution_epoch() { return execution_epoch; }
    public void setExecution_epoch(Long value) { execution_epoch = value; }
    public Long getNative_task_num() { return native_task_num; }
    public Boolean getPhysical_started() { return physical_started; }
    public void setPhysical_started(Boolean value) { physical_started = value; }
    public void setNative_task_num(Long value) { native_task_num = value; }
    public String getContract_fingerprint() { return contract_fingerprint; }
    public void setContract_fingerprint(String value) { contract_fingerprint = value; }
    public String getRequest_fingerprint() { return request_fingerprint; }
    public void setRequest_fingerprint(String value) { request_fingerprint = value; }
    public String getIdempotency_scope() { return idempotency_scope; }
    public void setIdempotency_scope(String value) { idempotency_scope = value; }
    public String getHeld_reason() { return held_reason; }
    public void setHeld_reason(String value) { held_reason = value; }
    public Map<String,Object> getResult() { return result; }
    public void setResult(Map<String,Object> value) { result = value; }
    public Map<String,Object> getOriginal_request() { return original_request; }
    public void setOriginal_request(Map<String,Object> value) { original_request = value; }

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private LocalDateTime dispatch_time;
    private LocalDateTime finish_time;

    /** 链归属（M10）：非空=某任务链的第 seq_no 段，由编排器物化；空=独立任务。 */
    private Long chain_id;
    private Long seq_no;
    private Long retry_count;
    private Long max_retry;

    /** 乐观锁：引擎推进 / 管理端取消 / 编排器三方写并发，updateById 版本不匹配即 0 行（防丢失更新）。 */
    @Version
    private Long version;

    public String getTask_no() {
        return task_no;
    }

    public void setTask_no(String task_no) {
        this.task_no = task_no;
    }

    public Long getApp_id() {
        return app_id;
    }

    public void setApp_id(Long app_id) {
        this.app_id = app_id;
    }

    public String getWms_ref() {
        return wms_ref;
    }

    public void setWms_ref(String wms_ref) {
        this.wms_ref = wms_ref;
    }

    public String getTask_type() {
        return task_type;
    }

    public void setTask_type(String task_type) {
        this.task_type = task_type;
    }

    public Long getTake_row() {
        return take_row;
    }

    public void setTake_row(Long take_row) {
        this.take_row = take_row;
    }

    public Long getTake_column() {
        return take_column;
    }

    public void setTake_column(Long take_column) {
        this.take_column = take_column;
    }

    public Long getTake_floor() {
        return take_floor;
    }

    public void setTake_floor(Long take_floor) {
        this.take_floor = take_floor;
    }

    public Long getPut_row() {
        return put_row;
    }

    public void setPut_row(Long put_row) {
        this.put_row = put_row;
    }

    public Long getPut_column() {
        return put_column;
    }

    public void setPut_column(Long put_column) {
        this.put_column = put_column;
    }

    public Long getPut_floor() {
        return put_floor;
    }

    public void setPut_floor(Long put_floor) {
        this.put_floor = put_floor;
    }

    public Long getPort_num() {
        return port_num;
    }

    public void setPort_num(Long port_num) {
        this.port_num = port_num;
    }

    public Long getPriority() {
        return priority;
    }

    public void setPriority(Long priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHandshake_step() {
        return handshake_step;
    }

    public void setHandshake_step(String handshake_step) {
        this.handshake_step = handshake_step;
    }

    public Long getError_code() {
        return error_code;
    }

    public void setError_code(Long error_code) {
        this.error_code = error_code;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public LocalDateTime getDispatch_time() {
        return dispatch_time;
    }

    public void setDispatch_time(LocalDateTime dispatch_time) {
        this.dispatch_time = dispatch_time;
    }

    public LocalDateTime getFinish_time() {
        return finish_time;
    }

    public void setFinish_time(LocalDateTime finish_time) {
        this.finish_time = finish_time;
    }

    public Long getChain_id() {
        return chain_id;
    }

    public void setChain_id(Long chain_id) {
        this.chain_id = chain_id;
    }

    public Long getSeq_no() {
        return seq_no;
    }

    public void setSeq_no(Long seq_no) {
        this.seq_no = seq_no;
    }

    public Long getRetry_count() {
        return retry_count;
    }

    public void setRetry_count(Long retry_count) {
        this.retry_count = retry_count;
    }

    public Long getMax_retry() {
        return max_retry;
    }

    public void setMax_retry(Long max_retry) {
        this.max_retry = max_retry;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
