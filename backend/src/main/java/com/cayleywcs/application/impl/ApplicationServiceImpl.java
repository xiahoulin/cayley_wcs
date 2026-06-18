package com.cayleywcs.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.system.CurrentUserProvider;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationServiceImpl implements ApplicationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ApplicationMapper applicationMapper;
    private final CurrentUserProvider currentUserProvider;

    public ApplicationServiceImpl(ApplicationMapper applicationMapper, CurrentUserProvider currentUserProvider) {
        this.applicationMapper = applicationMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public PageData<ApplicationEntity> page(PageSearch pageSearch) {
        List<ApplicationEntity> rows = listAll().stream()
                .filter(row -> matches(row, pageSearch))
                .toList();
        return PageSupport.slice(rows, pageSearch);
    }

    @Override
    public List<ApplicationEntity> listAll() {
        return applicationMapper.selectList(new QueryWrapper<ApplicationEntity>()
                .eq("\"is_valid\"", true)
                .orderByDesc("\"id\""));
    }

    @Override
    public ApplicationEntity getById(Long id) {
        ApplicationEntity entity = applicationMapper.selectById(id);
        if (entity == null || !Boolean.TRUE.equals(entity.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "application not found: " + id);
        }
        return entity;
    }

    @Override
    public ApplicationEntity getByAppKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return null;
        }
        return applicationMapper.selectOne(new QueryWrapper<ApplicationEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"app_key\"", appKey)
                .last("limit 1"));
    }

    @Override
    @Transactional
    public ApplicationEntity create(ApplicationEntity entity) {
        if (entity.getApp_key() == null || entity.getApp_key().isBlank()) {
            entity.setApp_key("ak_" + randomToken(18));
        }
        if (entity.getApp_secret() == null || entity.getApp_secret().isBlank()) {
            entity.setApp_secret(randomToken(32));
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("idle");
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        Audits.fillCreate(entity, currentUserProvider.currentUser());
        applicationMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public ApplicationEntity update(ApplicationEntity entity) {
        ApplicationEntity existing = getById(entity.getId());
        // app_key / app_secret 不通过普通更新修改，沿用既有值（改密走 resetSecret）。
        entity.setApp_key(existing.getApp_key());
        entity.setApp_secret(existing.getApp_secret());
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        Audits.touch(entity);
        applicationMapper.updateById(entity);
        return applicationMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        ApplicationEntity existing = applicationMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        return applicationMapper.updateById(existing) == 1;
    }

    @Override
    @Transactional
    public ApplicationEntity resetSecret(Long id) {
        ApplicationEntity existing = getById(id);
        existing.setApp_secret(randomToken(32));
        Audits.touch(existing);
        applicationMapper.updateById(existing);
        return existing;
    }

    @Override
    public ApplicationEntity validateForConnect(Long appId) {
        ApplicationEntity app = applicationMapper.selectById(appId);
        if (app == null || !Boolean.TRUE.equals(app.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "application not found: " + appId);
        }
        if (!Boolean.TRUE.equals(app.getEnabled())) {
            throw new WcsException(ErrorCode.APPKEY_INVALID, "application disabled: " + app.getApp_code());
        }
        if (app.getApp_key() == null || app.getApp_key().isBlank()) {
            throw new WcsException(ErrorCode.APPKEY_INVALID, "application app_key missing: " + app.getApp_code());
        }
        return app;
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return URL_ENCODER.encodeToString(buf);
    }

    private static boolean matches(ApplicationEntity row, PageSearch pageSearch) {
        if (pageSearch.getSearchObjects() == null || pageSearch.getSearchObjects().isEmpty()) {
            return true;
        }
        for (Map<String, Object> searchObject : pageSearch.getSearchObjects()) {
            String name = stringValue(searchObject.get("name"));
            String needle = stringValue(searchObject.getOrDefault("value", searchObject.get("text")));
            if (name.isBlank() || needle.isBlank()) {
                continue;
            }
            String haystack = switch (name) {
                case "app_code" -> row.getApp_code();
                case "app_name" -> row.getApp_name();
                case "direction" -> row.getDirection();
                case "status" -> row.getStatus();
                default -> "";
            };
            if (haystack == null || !haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
