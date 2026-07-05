package com.cayleywcs.webhook;

import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.webhook.entity.WebhookDeliveryEntity;
import com.cayleywcs.webhook.entity.WebhookEntity;
import java.util.List;

/** Webhook 订阅管理（M13，管理端）。 */
public interface WebhookService {

    PageData<WebhookEntity> page(PageSearch pageSearch);

    WebhookEntity getById(Long id);

    WebhookEntity create(WebhookEntity entity);

    WebhookEntity update(WebhookEntity entity);

    boolean delete(Long id);

    /** 某 webhook 最近的投递记录（观测/排障）。 */
    List<WebhookDeliveryEntity> deliveries(Long webhookId, int limit);
}
