package com.cayleywcs.protocol.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cayleywcs.common.entity.BaseEntity;
import java.math.BigDecimal;

/**
 * 协议点位/字段映射（协议数据格式落地，需求 1）。一条 = 协议里的一个字段（如 DB100.DBW0 / NodeId）。
 */
@TableName("\"wcs_protocol_point\"")
public class ProtocolPointEntity extends BaseEntity {
    private Long protocol_id;
    private String point_group;
    private String field_name;
    private String symbol_name;
    private String address;
    private String data_type;
    private String rw;
    private String value_range;
    private BigDecimal scale;
    private Long sort;
    private String description;

    public Long getProtocol_id() {
        return protocol_id;
    }

    public void setProtocol_id(Long protocol_id) {
        this.protocol_id = protocol_id;
    }

    public String getPoint_group() {
        return point_group;
    }

    public void setPoint_group(String point_group) {
        this.point_group = point_group;
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

    public String getRw() {
        return rw;
    }

    public void setRw(String rw) {
        this.rw = rw;
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
