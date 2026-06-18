package com.cayleywcs.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 报文留存（wcs_message_log）。高频，按需保留/清理。 */
@TableName("\"wcs_message_log\"")
public class MessageLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long app_id;
    private String direction;
    private String field_name;
    private String address;
    private String raw_value;
    private String json_payload;
    private LocalDateTime create_time;
    private Long tenant_id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getApp_id() {
        return app_id;
    }

    public void setApp_id(Long app_id) {
        this.app_id = app_id;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getField_name() {
        return field_name;
    }

    public void setField_name(String field_name) {
        this.field_name = field_name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getRaw_value() {
        return raw_value;
    }

    public void setRaw_value(String raw_value) {
        this.raw_value = raw_value;
    }

    public String getJson_payload() {
        return json_payload;
    }

    public void setJson_payload(String json_payload) {
        this.json_payload = json_payload;
    }

    public LocalDateTime getCreate_time() {
        return create_time;
    }

    public void setCreate_time(LocalDateTime create_time) {
        this.create_time = create_time;
    }

    public Long getTenant_id() {
        return tenant_id;
    }

    public void setTenant_id(Long tenant_id) {
        this.tenant_id = tenant_id;
    }
}
