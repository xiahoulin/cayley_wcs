package com.cayleywcs.application;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import java.util.List;

public interface ApplicationService {

    PageData<ApplicationEntity> page(PageSearch pageSearch);

    List<ApplicationEntity> listAll();

    ApplicationEntity getById(Long id);

    ApplicationEntity getByAppKey(String appKey);

    ApplicationEntity create(ApplicationEntity entity);

    ApplicationEntity update(ApplicationEntity entity);

    boolean delete(Long id);

    ApplicationEntity resetSecret(Long id);

    /**
     * 出站建连开闸校验（需求 3 / APP KEY 双向）：应用必须存在、未删除且 enabled。
     * 失败抛 {@link com.cayleywcs.common.exception.WcsException}。
     */
    ApplicationEntity validateForConnect(Long appId);

    /**
     * 改工作模式（M10 队列暂停/恢复、M11 维护）：normal/paused/maintenance。
     * 只更该列（防与其他管理端写并发时全行覆盖丢字段）。
     */
    void updateWorkMode(Long appId, String workMode);

    /**
     * 改连接期望态（M12）：open/closed。用户意图，只更该列（不校验存在性——由调用方 open/close 保证）。
     */
    void updateDesiredState(Long appId, String desiredState);
}
