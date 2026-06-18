package com.cayleywcs.protocol.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 协议表（需求 1）。data_format / transport_template 以 JSON 表示（text 存储 + JacksonTypeHandler）。
 */
@TableName(value = "\"wcs_protocol\"", autoResultMap = true)
public class ProtocolEntity extends BaseEntity {
    private String protocol_code;
    private String protocol_name;
    private String target_system;
    private String protocol_type;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode data_format;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private JsonNode transport_template;

    private String version;
    private String status;
    private String remark;

    public String getProtocol_code() {
        return protocol_code;
    }

    public void setProtocol_code(String protocol_code) {
        this.protocol_code = protocol_code;
    }

    public String getProtocol_name() {
        return protocol_name;
    }

    public void setProtocol_name(String protocol_name) {
        this.protocol_name = protocol_name;
    }

    public String getTarget_system() {
        return target_system;
    }

    public void setTarget_system(String target_system) {
        this.target_system = target_system;
    }

    public String getProtocol_type() {
        return protocol_type;
    }

    public void setProtocol_type(String protocol_type) {
        this.protocol_type = protocol_type;
    }

    public JsonNode getData_format() {
        return data_format;
    }

    public void setData_format(JsonNode data_format) {
        this.data_format = data_format;
    }

    public JsonNode getTransport_template() {
        return transport_template;
    }

    public void setTransport_template(JsonNode transport_template) {
        this.transport_template = transport_template;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
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
