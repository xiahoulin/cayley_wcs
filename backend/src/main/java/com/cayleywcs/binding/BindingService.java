package com.cayleywcs.binding;

import com.cayleywcs.binding.entity.AppBindingEntity;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import java.util.List;

/**
 * 应用绑定授权服务：CRUD + 下发授权判定。
 * 绑定为「上位侧应用 upstream → 下位侧应用 downstream」(upstream≠downstream，不允许自绑定)；不按 direction 锁方向。
 */
public interface BindingService {

    PageData<AppBindingEntity> page(PageSearch pageSearch);

    List<AppBindingEntity> listAll();

    AppBindingEntity getById(Long id);

    AppBindingEntity create(AppBindingEntity entity);

    AppBindingEntity update(AppBindingEntity entity);

    boolean delete(Long id);

    /**
     * 是否允许 upstream 指挥 downstream：存在 enabled 且未删除的绑定即放行。
     * 不限租户（内部/测试用）；下发鉴权请用带 tenantId 的重载。
     */
    boolean isAllowed(Long upstreamAppId, Long downstreamAppId);

    /** 租户隔离版：仅匹配 tenantId 下的绑定（与唯一索引含 tenant 对齐），用于 /open 下发越权判定。 */
    boolean isAllowed(Long upstreamAppId, Long downstreamAppId, Long tenantId);

    /** 同 {@link #isAllowed(Long, Long)}，不通过时抛 {@link com.cayleywcs.common.exception.WcsException}（FORBIDDEN）。 */
    void assertAllowed(Long upstreamAppId, Long downstreamAppId);

    /** 租户隔离版断言，用于 dispatch：tenantId 取调用方应用所属租户。 */
    void assertAllowed(Long upstreamAppId, Long downstreamAppId, Long tenantId);

    /** 某个上位侧应用已被授权指挥的下位侧应用 id 列表（enabled 且未删除）。供「按应用授权」页回显勾选。 */
    List<Long> grantedDownstreamIds(Long upstreamAppId);

    /**
     * 以上位侧应用为中心的整批授权（声明式对账）：把该应用的可指挥下位侧集合“设置为” downstreamAppIds。
     * 缺的补建、已撤选的软删，已存在的保持/重新启用，幂等。自指(downstream==upstream)被忽略。返回对账后的下位侧 id 列表。
     */
    List<Long> grant(Long upstreamAppId, List<Long> downstreamAppIds, String scope);
}
