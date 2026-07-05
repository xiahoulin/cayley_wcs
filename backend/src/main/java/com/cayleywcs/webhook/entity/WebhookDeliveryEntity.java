package com.cayleywcs.webhook.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cayleywcs.common.entity.BaseEntity;
import java.time.LocalDateTime;

/** Webhook 投递记录（wcs_webhook_delivery）：一行一个 (webhook,event_type,biz_id)，attempts 计数 + 退避重投。 */
@TableName("\"wcs_webhook_delivery\"")
public class WebhookDeliveryEntity extends BaseEntity {
    private Long webhook_id;
    private String event_type;
    private Long biz_id;
    private String payload;
    private String status;
    private Long attempts;
    private LocalDateTime next_retry_time;
    private String last_response;

    public Long getWebhook_id() {
        return webhook_id;
    }

    public void setWebhook_id(Long webhook_id) {
        this.webhook_id = webhook_id;
    }

    public String getEvent_type() {
        return event_type;
    }

    public void setEvent_type(String event_type) {
        this.event_type = event_type;
    }

    public Long getBiz_id() {
        return biz_id;
    }

    public void setBiz_id(Long biz_id) {
        this.biz_id = biz_id;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getAttempts() {
        return attempts;
    }

    public void setAttempts(Long attempts) {
        this.attempts = attempts;
    }

    public LocalDateTime getNext_retry_time() {
        return next_retry_time;
    }

    public void setNext_retry_time(LocalDateTime next_retry_time) {
        this.next_retry_time = next_retry_time;
    }

    public String getLast_response() {
        return last_response;
    }

    public void setLast_response(String last_response) {
        this.last_response = last_response;
    }
}
