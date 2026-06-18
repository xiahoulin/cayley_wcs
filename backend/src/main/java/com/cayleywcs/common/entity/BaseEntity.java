package com.cayleywcs.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;

/**
 * 标准列基类，对齐 CayleyWMS：id + creator/create_time/last_update_time/is_valid(软删)/tenant_id。
 * MyBatis-Plus 会把继承字段一并作为表列映射（蛇形列名 = 字段名，map-underscore-to-camel-case=false）。
 */
public abstract class BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String creator;
    private LocalDateTime create_time;
    private LocalDateTime last_update_time;
    private Boolean is_valid;
    private Long tenant_id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public LocalDateTime getCreate_time() {
        return create_time;
    }

    public void setCreate_time(LocalDateTime create_time) {
        this.create_time = create_time;
    }

    public LocalDateTime getLast_update_time() {
        return last_update_time;
    }

    public void setLast_update_time(LocalDateTime last_update_time) {
        this.last_update_time = last_update_time;
    }

    public Boolean getIs_valid() {
        return is_valid;
    }

    public void setIs_valid(Boolean is_valid) {
        this.is_valid = is_valid;
    }

    public Long getTenant_id() {
        return tenant_id;
    }

    public void setTenant_id(Long tenant_id) {
        this.tenant_id = tenant_id;
    }
}
