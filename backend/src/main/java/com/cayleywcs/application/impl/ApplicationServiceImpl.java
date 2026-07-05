package com.cayleywcs.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.device.DeviceHandlerRegistry;
import com.cayleywcs.system.CurrentUserProvider;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationServiceImpl implements ApplicationService {
    /** 不参与设备调度的类型（上位系统），无需已注册 handler。 */
    private static final String DEVICE_TYPE_NONE = "none";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ApplicationMapper applicationMapper;
    private final CurrentUserProvider currentUserProvider;
    // ObjectProvider 延迟解析，切断构造环：本类 → Registry → StackerHandler → ConnectionManager → 本接口
    private final ObjectProvider<DeviceHandlerRegistry> handlerRegistryProvider;

    public ApplicationServiceImpl(ApplicationMapper applicationMapper, CurrentUserProvider currentUserProvider,
                                  ObjectProvider<DeviceHandlerRegistry> handlerRegistryProvider) {
        this.applicationMapper = applicationMapper;
        this.currentUserProvider = currentUserProvider;
        this.handlerRegistryProvider = handlerRegistryProvider;
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
        validateDeviceType(entity.getDevice_type());
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
        validateDeviceType(entity.getDevice_type());
        ApplicationEntity existing = getById(entity.getId());
        // app_key / app_secret 不通过普通更新修改，沿用既有值（改密走 resetSecret）。
        entity.setApp_key(existing.getApp_key());
        entity.setApp_secret(existing.getApp_secret());
        // work_mode/dispatch_strategy/failure_policy/task_stale_seconds/desired_state 由专用接口独占：
        // 实体已标注 @TableField(updateStrategy=NEVER)，此处 updateById 永不写这些列，无需读快照沿用（对抗审查 P2）。
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

    private static final Set<String> WORK_MODES = Set.of("normal", "paused", "maintenance");

    @Override
    @Transactional
    public void updateWorkMode(Long appId, String workMode) {
        if (workMode == null || !WORK_MODES.contains(workMode)) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "非法工作模式: " + workMode + "，可用: " + WORK_MODES);
        }
        getById(appId); // 存在性校验
        applicationMapper.update(null, new UpdateWrapper<ApplicationEntity>()
                .eq("\"id\"", appId)
                .set("\"work_mode\"", workMode)
                .set("\"last_update_time\"", LocalDateTime.now()));
    }

    private static final Set<String> DESIRED_STATES = Set.of("open", "closed");

    @Override
    @Transactional
    public void updateDesiredState(Long appId, String desiredState) {
        if (!DESIRED_STATES.contains(desiredState)) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "非法期望态: " + desiredState);
        }
        applicationMapper.update(null, new UpdateWrapper<ApplicationEntity>()
                .eq("\"id\"", appId)
                .set("\"desired_state\"", desiredState)
                .set("\"last_update_time\"", LocalDateTime.now()));
    }

    /**
     * 设备类型必须有已注册 handler（未知类型不静默，否则任务永久 pending）；
     * 空值走 DB 默认 stacker；none=上位系统不参与设备调度。
     */
    private void validateDeviceType(String deviceType) {
        if (deviceType == null || deviceType.isBlank() || DEVICE_TYPE_NONE.equals(deviceType)) {
            return;
        }
        DeviceHandlerRegistry registry = handlerRegistryProvider.getIfAvailable();
        if (registry != null && !registry.supports(deviceType)) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "未注册的设备类型: " + deviceType
                    + "，可用: " + registry.supportedTypes() + " 或 " + DEVICE_TYPE_NONE);
        }
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
