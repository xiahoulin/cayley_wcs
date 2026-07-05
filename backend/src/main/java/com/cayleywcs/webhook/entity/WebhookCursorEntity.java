package com.cayleywcs.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Webhook 事件源游标（wcs_webhook_cursor）：每 (webhook, source) 一行，keyset = (watermark_millis, last_id)。 */
@TableName("\"wcs_webhook_cursor\"")
public class WebhookCursorEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long webhook_id;
    private String source;
    private LocalDateTime watermark_time;
    private Long last_id;
    private LocalDateTime last_update_time;
    private Long tenant_id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getWebhook_id() {
        return webhook_id;
    }

    public void setWebhook_id(Long webhook_id) {
        this.webhook_id = webhook_id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getWatermark_time() {
        return watermark_time;
    }

    public void setWatermark_time(LocalDateTime watermark_time) {
        this.watermark_time = watermark_time;
    }

    public Long getLast_id() {
        return last_id;
    }

    public void setLast_id(Long last_id) {
        this.last_id = last_id;
    }

    public LocalDateTime getLast_update_time() {
        return last_update_time;
    }

    public void setLast_update_time(LocalDateTime last_update_time) {
        this.last_update_time = last_update_time;
    }

    public Long getTenant_id() {
        return tenant_id;
    }

    public void setTenant_id(Long tenant_id) {
        this.tenant_id = tenant_id;
    }
}
