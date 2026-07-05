package com.cayleywcs.webhook.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.cayleywcs.common.entity.BaseEntity;
import java.util.List;

/** Webhook 订阅（wcs_webhook）。event_types/scope_app_ids 为 JSON（text 存储）。 */
@TableName(value = "\"wcs_webhook\"", autoResultMap = true)
public class WebhookEntity extends BaseEntity {
    private String name;
    private String url;
    private String secret;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> event_types;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> scope_app_ids;

    private Boolean enabled;
    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public List<String> getEvent_types() {
        return event_types;
    }

    public void setEvent_types(List<String> event_types) {
        this.event_types = event_types;
    }

    public List<Long> getScope_app_ids() {
        return scope_app_ids;
    }

    public void setScope_app_ids(List<Long> scope_app_ids) {
        this.scope_app_ids = scope_app_ids;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
