package com.cayleywcs.application.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 应用信息表（需求 3）：可对接的应用/设备。app_key 用于双向鉴权，protocol_id 关联协议表。
 * conn_params 为 JSON（host/port/endpoint/unitId 等连接参数）。
 */
@TableName(value = "\"wcs_application\"", autoResultMap = true)
public class ApplicationEntity extends BaseEntity {
    private String app_code;
    private String app_name;
    private String app_key;
    private String app_secret;
    private Long protocol_id;
    private String direction;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode conn_params;

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

    public JsonNode getConn_params() {
        return conn_params;
    }

    public void setConn_params(JsonNode conn_params) {
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
