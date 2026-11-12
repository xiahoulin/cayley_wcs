package com.cayleywcs.protocol.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 协议点位/字段映射。一条 = 协议里的一个字段（如 DB100.DBW0 / NodeId）。
 */
@TableName(value = "\"wcs_protocol_point\"", autoResultMap = true)
public class ProtocolPointEntity extends BaseEntity {
    private Long protocol_id;
    private String field_name;
    private String symbol_name;
    private String address;
    private String data_type;
    private String value_range;
    private BigDecimal scale;
    private Long sort;
    private String description;
    private String template_code;
    private Long template_version;
    private String operation;
    private String direction;
    private String point_scope;
    private String logical_type;
    private Boolean required;
    private Boolean nullable;
    private Long row_version;
    private String active_identity;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> constraints;
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Object default_value;

    public String getTemplate_code() { return template_code; }
    public void setTemplate_code(String value) { template_code = value; }
    public Long getTemplate_version() { return template_version; }
    public void setTemplate_version(Long value) { template_version = value; }
    public String getOperation() { return operation; }
    public void setOperation(String value) { operation = value; }
    public String getDirection() { return direction; }
    public void setDirection(String value) { direction = value; }
    public String getPoint_scope() { return point_scope; }
    public void setPoint_scope(String value) { point_scope = value; }
    public String getLogical_type() { return logical_type; }
    public void setLogical_type(String value) { logical_type = value; }
    public Boolean getRequired() { return required; }
    public void setRequired(Boolean value) { required = value; }
    public Boolean getNullable() { return nullable; }
    public void setNullable(Boolean value) { nullable = value; }
    public Long getRow_version() { return row_version; }
    public void setRow_version(Long value) { row_version = value; }
    public String getActive_identity() { return active_identity; }
    public void setActive_identity(String value) { active_identity = value; }
    public Map<String, Object> getConstraints() { return constraints; }
    public void setConstraints(Map<String, Object> value) { constraints = value; }
    public Object getDefault_value() { return default_value; }
    public void setDefault_value(Object value) { default_value = value; }

    public Long getProtocol_id() {
        return protocol_id;
    }

    public void setProtocol_id(Long protocol_id) {
        this.protocol_id = protocol_id;
    }

    public String getField_name() {
        return field_name;
    }

    public void setField_name(String field_name) {
        this.field_name = field_name;
    }

    public String getSymbol_name() {
        return symbol_name;
    }

    public void setSymbol_name(String symbol_name) {
        this.symbol_name = symbol_name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getData_type() {
        return data_type;
    }

    public void setData_type(String data_type) {
        this.data_type = data_type;
    }

    public String getValue_range() {
        return value_range;
    }

    public void setValue_range(String value_range) {
        this.value_range = value_range;
    }

    public BigDecimal getScale() {
        return scale;
    }

    public void setScale(BigDecimal scale) {
        this.scale = scale;
    }

    public Long getSort() {
        return sort;
    }

    public void setSort(Long sort) {
        this.sort = sort;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
