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
}
