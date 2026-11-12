package com.cayleywcs.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 任务链（wcs_task_chain）：一对多编排的逻辑任务。段定义存 segments JSON，
 * 编排器按段串行物化子任务（wcs_task.chain_id/seq_no 回指）。
 */
@TableName(value = "\"wcs_task_chain\"", autoResultMap = true)
public class TaskChainEntity extends BaseEntity {
    private String chain_no;
    private String held_reason;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String,Object>> original_segments;
    public String getHeld_reason() { return held_reason; }
    public void setHeld_reason(String value) { held_reason=value; }
    public List<Map<String,Object>> getOriginal_segments() { return original_segments; }
    public void setOriginal_segments(List<Map<String,Object>> value) { original_segments=value; }
    private String request_fingerprint;
    public String getRequest_fingerprint() { return request_fingerprint; }
    public void setRequest_fingerprint(String value) { request_fingerprint=value; }
    private Long upstream_app_id;
    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> segments;

    private String select_strategy;
    private Long priority;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    private String wms_ref;
    private String error_message;
    private LocalDateTime finish_time;

    /** 乐观锁：编排器推进 vs 管理端取消并发写防丢失更新。 */
    @Version
    private Long version;

    public String getChain_no() {
        return chain_no;
    }

    public void setChain_no(String chain_no) {
        this.chain_no = chain_no;
    }

    public Long getUpstream_app_id() {
        return upstream_app_id;
    }

    public void setUpstream_app_id(Long upstream_app_id) {
        this.upstream_app_id = upstream_app_id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Map<String, Object>> getSegments() {
        return segments;
    }

    public void setSegments(List<Map<String, Object>> segments) {
        this.segments = segments;
    }

    public String getSelect_strategy() {
        return select_strategy;
    }

    public void setSelect_strategy(String select_strategy) {
        this.select_strategy = select_strategy;
    }

    public Long getPriority() {
        return priority;
    }

    public void setPriority(Long priority) {
        this.priority = priority;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public String getWms_ref() {
        return wms_ref;
    }

    public void setWms_ref(String wms_ref) {
        this.wms_ref = wms_ref;
    }

    public String getError_message() {
        return error_message;
    }

    public void setError_message(String error_message) {
        this.error_message = error_message;
    }

    public LocalDateTime getFinish_time() {
        return finish_time;
    }

    public void setFinish_time(LocalDateTime finish_time) {
        this.finish_time = finish_time;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
