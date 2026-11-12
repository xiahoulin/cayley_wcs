package com.cayleywcs.webhook.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.system.CurrentUserProvider;
import com.cayleywcs.webhook.WebhookDeliveryWorker;
import com.cayleywcs.webhook.WebhookService;
import com.cayleywcs.webhook.entity.WebhookDeliveryEntity;
import com.cayleywcs.webhook.entity.WebhookEntity;
import com.cayleywcs.webhook.mapper.WebhookDeliveryMapper;
import com.cayleywcs.webhook.mapper.WebhookMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookServiceImpl implements WebhookService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.application.ApplicationService applications;
    private final WebhookMapper webhookMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final CurrentUserProvider currentUserProvider;
    private final WebhookDeliveryWorker deliveryWorker;

    public WebhookServiceImpl(WebhookMapper webhookMapper, WebhookDeliveryMapper deliveryMapper,
                              CurrentUserProvider currentUserProvider, WebhookDeliveryWorker deliveryWorker) {
        this.webhookMapper = webhookMapper;
        this.deliveryMapper = deliveryMapper;
        this.currentUserProvider = currentUserProvider;
        this.deliveryWorker = deliveryWorker;
    }

    @Override
    public PageData<WebhookEntity> page(PageSearch pageSearch) {
        List<WebhookEntity> rows = webhookMapper.selectList(new QueryWrapper<WebhookEntity>()
                .eq("\"is_valid\"", true)
                .orderByDesc("\"id\""));
        return PageSupport.slice(rows, pageSearch);
    }

    @Override
    public WebhookEntity getById(Long id) {
        WebhookEntity e = webhookMapper.selectById(id);
        if (e == null || !Boolean.TRUE.equals(e.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "webhook not found: " + id);
        }
        return e;
    }

    @Override
    @Transactional
    public WebhookEntity create(WebhookEntity entity) {
        lifecycle.references();
        validate(entity);
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        Audits.fillCreate(entity, currentUserProvider.currentUser());
        webhookMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public WebhookEntity update(WebhookEntity entity) {
        lifecycle.references();
        WebhookEntity existing = getById(entity.getId());
        validate(entity);
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        Audits.touch(entity);
        webhookMapper.updateById(entity);
        return webhookMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        WebhookEntity existing = webhookMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        return webhookMapper.updateById(existing) == 1;
    }

    @Override
    public List<WebhookDeliveryEntity> deliveries(Long webhookId, int limit) {
        int n = limit <= 0 ? 50 : Math.min(limit, 500);
        return deliveryMapper.selectList(new QueryWrapper<WebhookDeliveryEntity>()
                .eq("\"webhook_id\"", webhookId)
                .orderByDesc("\"id\"")
                .last("limit " + n));
    }

    private void validate(WebhookEntity entity) {
        if(entity.getScope_app_ids()!=null) for(Long appId:entity.getScope_app_ids()) applications.getById(appId);
        if (entity.getName() == null || entity.getName().isBlank()) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "name 必填");
        }
        // URL 合法性/SSRF 校验复用投递 worker（同一套规则），创建即拦截非法目标
        String err = deliveryWorker.validateUrl(entity.getUrl());
        if (err != null) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "url 非法: " + err);
        }
    }
}
