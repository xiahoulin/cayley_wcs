package com.cayleywcs.webhook;

import com.cayleywcs.common.api.ApiResponse;
import com.cayleywcs.common.api.IdRequest;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.webhook.entity.WebhookDeliveryEntity;
import com.cayleywcs.webhook.entity.WebhookEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Webhook 订阅管理端（M13，JWT）：CRUD + 投递记录查询。 */
@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Webhook")
public class WebhookController {
    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhook/list")
    public ApiResponse<PageData<WebhookEntity>> list(@RequestBody PageSearch pageSearch) {
        return ApiResponse.success(webhookService.page(pageSearch));
    }

    @PostMapping("/webhook/detail")
    public ApiResponse<WebhookEntity> detail(@RequestBody IdRequest request) {
        return ApiResponse.success(webhookService.getById(request.id()));
    }

    @PostMapping("/webhook/create")
    @Operation(summary = "注册 Webhook 订阅（event_types 如 task.completed/alarm.raised；scope_app_ids 为空=全部）")
    public ApiResponse<WebhookEntity> create(@RequestBody WebhookEntity entity) {
        return ApiResponse.success(webhookService.create(entity));
    }

    @PostMapping("/webhook/update")
    public ApiResponse<WebhookEntity> update(@RequestBody WebhookEntity entity) {
        return ApiResponse.success(webhookService.update(entity));
    }

    @PostMapping("/webhook/delete")
    public ApiResponse<Boolean> delete(@RequestBody IdRequest request) {
        return ApiResponse.success(webhookService.delete(request.id()));
    }

    @PostMapping("/webhook/deliveries")
    @Operation(summary = "某 Webhook 最近投递记录（排障）")
    public ApiResponse<List<WebhookDeliveryEntity>> deliveries(@RequestBody DeliveryQuery q) {
        return ApiResponse.success(webhookService.deliveries(q.webhookId(), q.limit()));
    }

    public record DeliveryQuery(Long webhookId, int limit) {
    }
}
