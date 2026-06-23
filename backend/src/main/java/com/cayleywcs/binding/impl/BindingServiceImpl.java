package com.cayleywcs.binding.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.binding.BindingService;
import com.cayleywcs.binding.entity.AppBindingEntity;
import com.cayleywcs.binding.mapper.AppBindingMapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.system.CurrentUserProvider;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BindingServiceImpl implements BindingService {
    private final AppBindingMapper bindingMapper;
    private final CurrentUserProvider currentUserProvider;

    public BindingServiceImpl(AppBindingMapper bindingMapper, CurrentUserProvider currentUserProvider) {
        this.bindingMapper = bindingMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public PageData<AppBindingEntity> page(PageSearch pageSearch) {
        return PageSupport.slice(listAll(), pageSearch);
    }

    @Override
    public List<AppBindingEntity> listAll() {
        return bindingMapper.selectList(new QueryWrapper<AppBindingEntity>()
                .eq("\"is_valid\"", true)
                .orderByDesc("\"id\""));
    }

    @Override
    public AppBindingEntity getById(Long id) {
        AppBindingEntity entity = bindingMapper.selectById(id);
        if (entity == null || !Boolean.TRUE.equals(entity.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "binding not found: " + id);
        }
        return entity;
    }

    @Override
    @Transactional
    public AppBindingEntity create(AppBindingEntity entity) {
        validatePair(entity.getUpstream_app_id(), entity.getDownstream_app_id());
        var user = currentUserProvider.currentUser();
        Long tenant = user.tenantId();
        String scope = (entity.getScope() == null || entity.getScope().isBlank()) ? "dispatch" : entity.getScope();
        boolean enabled = entity.getEnabled() == null || Boolean.TRUE.equals(entity.getEnabled());
        // 复用同 (上位,下位,租户) 的既有行（含软删）：唯一索引 uk_wcs_app_binding_pair 不含 is_valid，
        // 软删行仍占唯一槽位，直接 insert 会撞约束抛裸 DB 异常。命中软删/停用行则复活，命中活跃行才 CONFLICT。
        AppBindingEntity row = findPair(entity.getUpstream_app_id(), entity.getDownstream_app_id(), tenant);
        if (row != null) {
            if (Boolean.TRUE.equals(row.getIs_valid()) && Boolean.TRUE.equals(row.getEnabled())) {
                throw new WcsException(ErrorCode.CONFLICT,
                        "绑定已存在：" + entity.getUpstream_app_id() + "->" + entity.getDownstream_app_id());
            }
            row.setIs_valid(true);
            row.setEnabled(enabled);
            row.setScope(scope);
            row.setRemark(entity.getRemark());
            Audits.touch(row);
            bindingMapper.updateById(row);
            return row;
        }
        entity.setScope(scope);
        entity.setEnabled(enabled);
        Audits.fillCreate(entity, user);
        bindingMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public AppBindingEntity update(AppBindingEntity entity) {
        AppBindingEntity existing = getById(entity.getId());
        validatePair(entity.getUpstream_app_id(), entity.getDownstream_app_id());
        rejectDuplicate(entity.getUpstream_app_id(), entity.getDownstream_app_id(), existing.getTenant_id(), entity.getId());
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        if (entity.getScope() == null || entity.getScope().isBlank()) {
            entity.setScope("dispatch");
        }
        Audits.touch(entity);
        bindingMapper.updateById(entity);
        return bindingMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        AppBindingEntity existing = bindingMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        return bindingMapper.updateById(existing) == 1;
    }

    @Override
    public boolean isAllowed(Long upstreamAppId, Long downstreamAppId) {
        return isAllowed(upstreamAppId, downstreamAppId, null);
    }

    @Override
    public boolean isAllowed(Long upstreamAppId, Long downstreamAppId, Long tenantId) {
        if (upstreamAppId == null || downstreamAppId == null) {
            return false;
        }
        QueryWrapper<AppBindingEntity> wrapper = new QueryWrapper<AppBindingEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"enabled\"", true)
                .eq("\"upstream_app_id\"", upstreamAppId)
                .eq("\"downstream_app_id\"", downstreamAppId);
        if (tenantId != null) { // dispatch 鉴权按调用方租户隔离；null 表示不限租户（内部/测试）。
            wrapper.eq("\"tenant_id\"", tenantId);
        }
        Long hit = bindingMapper.selectCount(wrapper);
        return hit != null && hit > 0;
    }

    @Override
    public void assertAllowed(Long upstreamAppId, Long downstreamAppId) {
        assertAllowed(upstreamAppId, downstreamAppId, null);
    }

    @Override
    public void assertAllowed(Long upstreamAppId, Long downstreamAppId, Long tenantId) {
        if (!isAllowed(upstreamAppId, downstreamAppId, tenantId)) {
            throw new WcsException(ErrorCode.FORBIDDEN,
                    "未授权指挥该应用：upstream=" + upstreamAppId + " downstream=" + downstreamAppId);
        }
    }

    @Override
    public List<Long> grantedDownstreamIds(Long upstreamAppId) {
        if (upstreamAppId == null) {
            return List.of();
        }
        return bindingMapper.selectList(new QueryWrapper<AppBindingEntity>()
                        .eq("\"is_valid\"", true)
                        .eq("\"enabled\"", true)
                        .eq("\"upstream_app_id\"", upstreamAppId))
                .stream()
                .map(AppBindingEntity::getDownstream_app_id)
                .distinct()
                .toList();
    }

    @Override
    @Transactional
    public List<Long> grant(Long upstreamAppId, List<Long> downstreamAppIds, String scope) {
        if (upstreamAppId == null) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "upstream_app_id 必填");
        }
        var user = currentUserProvider.currentUser();
        Long tenant = user.tenantId();
        String effectiveScope = (scope == null || scope.isBlank()) ? "dispatch" : scope;
        Set<Long> desired = new LinkedHashSet<>();
        if (downstreamAppIds != null) {
            for (Long id : downstreamAppIds) {
                if (id != null && !id.equals(upstreamAppId)) { // 忽略自指：不允许自绑定
                    desired.add(id);
                }
            }
        }
        // 仅当前租户、该上位侧的全部绑定（含已软删，便于复用唯一槽位）。
        // 按 tenant 过滤与唯一索引 (上位,下位,租户) 对齐，避免误删/复活其他租户的授权行。
        List<AppBindingEntity> existing = bindingMapper.selectList(new QueryWrapper<AppBindingEntity>()
                .eq("\"upstream_app_id\"", upstreamAppId)
                .eq("\"tenant_id\"", tenant));
        Map<Long, AppBindingEntity> byDownstream = new LinkedHashMap<>();
        for (AppBindingEntity b : existing) {
            byDownstream.putIfAbsent(b.getDownstream_app_id(), b);
        }
        // 补建 / 复活 / 收敛 scope 到期望集合（声明式幂等）。
        for (Long downstreamId : desired) {
            AppBindingEntity row = byDownstream.get(downstreamId);
            if (row == null) {
                AppBindingEntity created = new AppBindingEntity();
                created.setUpstream_app_id(upstreamAppId);
                created.setDownstream_app_id(downstreamId);
                created.setScope(effectiveScope);
                created.setEnabled(true);
                created.setRemark("按应用授权");
                Audits.fillCreate(created, user);
                bindingMapper.insert(created);
            } else {
                boolean changed = false;
                if (!Boolean.TRUE.equals(row.getEnabled()) || !Boolean.TRUE.equals(row.getIs_valid())) {
                    row.setEnabled(true);
                    row.setIs_valid(true);
                    changed = true;
                }
                if (!effectiveScope.equals(row.getScope())) { // 声明式：把 scope 收敛到入参
                    row.setScope(effectiveScope);
                    changed = true;
                }
                if (changed) {
                    Audits.touch(row);
                    bindingMapper.updateById(row);
                }
            }
        }
        // 撤销不在期望集合内的（软删）。
        for (AppBindingEntity row : existing) {
            if (!desired.contains(row.getDownstream_app_id()) && Boolean.TRUE.equals(row.getIs_valid())) {
                row.setIs_valid(false);
                Audits.touch(row);
                bindingMapper.updateById(row);
            }
        }
        return grantedDownstreamIds(upstreamAppId);
    }

    private static void validatePair(Long upstreamId, Long downstreamId) {
        if (upstreamId == null || downstreamId == null) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "upstream_app_id / downstream_app_id 必填");
        }
        if (upstreamId.equals(downstreamId)) {
            throw new WcsException(ErrorCode.BAD_REQUEST, "不允许自绑定：应用不能指挥自己 (" + upstreamId + ")");
        }
    }

    private void rejectDuplicate(Long upstreamId, Long downstreamId, Long tenantId, Long excludeId) {
        // 与唯一索引 uk_wcs_app_binding_pair=(上位,下位,租户) 口径对齐：含软删行一并视为占位（不过滤 is_valid），
        // 否则 update() 改成与某软删行相同的 (上位,下位) 会绕过本校验、直撞 DB 唯一约束抛裸异常。
        QueryWrapper<AppBindingEntity> wrapper = new QueryWrapper<AppBindingEntity>()
                .eq("\"upstream_app_id\"", upstreamId)
                .eq("\"downstream_app_id\"", downstreamId)
                .eq("\"tenant_id\"", tenantId);
        if (excludeId != null) {
            wrapper.ne("\"id\"", excludeId);
        }
        Long dup = bindingMapper.selectCount(wrapper);
        if (dup != null && dup > 0) {
            throw new WcsException(ErrorCode.CONFLICT, "绑定已存在：" + upstreamId + "->" + downstreamId);
        }
    }

    /** 找同 (上位,下位,租户) 的既有行（含软删，唯一索引保证 ≤1 条）；无则 null。 */
    private AppBindingEntity findPair(Long upstreamId, Long downstreamId, Long tenantId) {
        return bindingMapper.selectOne(new QueryWrapper<AppBindingEntity>()
                .eq("\"upstream_app_id\"", upstreamId)
                .eq("\"downstream_app_id\"", downstreamId)
                .eq("\"tenant_id\"", tenantId)
                .last("limit 1"));
    }
}
