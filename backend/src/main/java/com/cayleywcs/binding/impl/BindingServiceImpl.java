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
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BindingServiceImpl implements BindingService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private final AppBindingMapper bindingMapper;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationMapper applicationMapper;
    private final ProtocolContractService contracts;

    public BindingServiceImpl(AppBindingMapper bindingMapper, CurrentUserProvider currentUserProvider,
                              ApplicationMapper applicationMapper, ProtocolContractService contracts) {
        this.bindingMapper = bindingMapper;
        this.currentUserProvider = currentUserProvider;
        this.applicationMapper = applicationMapper;
        this.contracts = contracts;
    }

    @Override
    public PageData<AppBindingEntity> page(PageSearch pageSearch) {
        return PageSupport.slice(listAll(), pageSearch);
    }

    @Override
    public List<AppBindingEntity> listAll() {
        var query=new QueryWrapper<AppBindingEntity>().eq("\"is_valid\"",true);
        var user=currentUserProvider.systemUser();
        if (!(Long.valueOf(0).equals(user.userId()) && "system".equals(user.userRole()))) query.eq("\"tenant_id\"",user.tenantId());
        return bindingMapper.selectList(query.orderByDesc("\"id\""));
    }

    @Override
    public AppBindingEntity getById(Long id) {
        AppBindingEntity entity = bindingMapper.selectById(id);
        if (entity == null || !Boolean.TRUE.equals(entity.getIs_valid())) {
            throw new WcsException(ErrorCode.NOT_FOUND, "binding not found: " + id);
        }
        var user=currentUserProvider.systemUser();
        if (!(Long.valueOf(0).equals(user.userId()) && "system".equals(user.userRole())) && !java.util.Objects.equals(entity.getTenant_id(),user.tenantId()))
            throw new WcsException(ErrorCode.NOT_FOUND,"绑定不存在或不可访问");
        return entity;
    }

    @Override
    @Transactional
    public AppBindingEntity create(AppBindingEntity entity) {
        if(lifecycle!=null) lifecycle.references();
        validatePair(entity.getUpstream_app_id(), entity.getDownstream_app_id());
        var match = checkedMatch(entity.getUpstream_app_id(), entity.getDownstream_app_id());
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
            pin(row, match);
            Audits.touch(row);
            bindingMapper.updateById(row);
            return row;
        }
        entity.setScope(scope);
        entity.setEnabled(enabled);
        pin(entity, match);
        Audits.fillCreate(entity, user);
        bindingMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public AppBindingEntity update(AppBindingEntity entity) {
        if(lifecycle!=null) lifecycle.references();
        AppBindingEntity existing = getById(entity.getId());
        validatePair(entity.getUpstream_app_id(), entity.getDownstream_app_id());
        var match = checkedMatch(entity.getUpstream_app_id(), entity.getDownstream_app_id());
        rejectDuplicate(entity.getUpstream_app_id(), entity.getDownstream_app_id(), existing.getTenant_id(), entity.getId());
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        pin(entity, match);
        if (entity.getRow_version() == null || !entity.getRow_version().equals(existing.getRow_version())) {
            throw new WcsException(ErrorCode.CONFLICT, "绑定已更新，请刷新后重试");
        }
        if (entity.getScope() == null || entity.getScope().isBlank()) {
            entity.setScope("dispatch");
        }
        Audits.touch(entity);
        if (bindingMapper.updateById(entity) != 1) throw new WcsException(ErrorCode.CONFLICT, "绑定并发更新冲突");
        return bindingMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        AppBindingEntity existing = getById(id);
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
        AppBindingEntity row = bindingMapper.selectOne(wrapper.last("limit 1"));
        if (row == null) return false;
        try {
            var match = checkedMatch(upstreamAppId, downstreamAppId, tenantId);
            return row.getContract_fingerprint() != null
                    && row.getContract_fingerprint().equals(match.fingerprint())
                    && java.util.Objects.equals(row.getUpstream_revision_id(), match.upstreamSnapshotId())
                    && java.util.Objects.equals(row.getDownstream_revision_id(), match.downstreamSnapshotId());
        } catch (WcsException | IllegalArgumentException ex) {
            return false;
        }
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
        return grantedDownstreamIds(upstreamAppId, null);
    }

    @Override
    public List<Long> grantedDownstreamIds(Long upstreamAppId, Long tenantId) {
        if (upstreamAppId == null) {
            return List.of();
        }
        QueryWrapper<AppBindingEntity> wrapper = new QueryWrapper<AppBindingEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"enabled\"", true)
                .eq("\"upstream_app_id\"", upstreamAppId);
        if (tenantId != null) { // 与 isAllowed(…, tenantId) 口径一致：/open 可见范围按调用方租户隔离
            wrapper.eq("\"tenant_id\"", tenantId);
        }
        return bindingMapper.selectList(wrapper)
                .stream()
                .filter(row -> isAllowed(upstreamAppId, row.getDownstream_app_id(), tenantId))
                .map(AppBindingEntity::getDownstream_app_id)
                .distinct()
                .toList();
    }

    @Override
    @Transactional
    public List<Long> grant(Long upstreamAppId, List<Long> downstreamAppIds, String scope) {
        if(lifecycle!=null) lifecycle.references();
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
        Map<Long, ProtocolContractService.Match> checked = new LinkedHashMap<>();
        for (Long id : desired) checked.put(id, checkedMatch(upstreamAppId, id));
        // 全部匹配成功后才开始写授权，任意一项失配保持原集合不变。
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
                pin(created, checked.get(downstreamId));
                Audits.fillCreate(created, user);
                bindingMapper.insert(created);
            } else {
                boolean changed = !java.util.Objects.equals(row.getContract_fingerprint(), checked.get(downstreamId).fingerprint());
                pin(row, checked.get(downstreamId));
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

    private ProtocolContractService.Match checkedMatch(Long upstreamId, Long downstreamId) {
        return checkedMatch(upstreamId,downstreamId,currentUserProvider.systemUser().tenantId());
    }

    private ProtocolContractService.Match checkedMatch(Long upstreamId, Long downstreamId,Long tenant) {
        validatePair(upstreamId, downstreamId);
        ApplicationEntity up = applicationMapper.selectById(upstreamId);
        ApplicationEntity down = applicationMapper.selectById(downstreamId);
        if (tenant==null) tenant=currentUserProvider.systemUser().tenantId();
        if (up == null || down == null || !Boolean.TRUE.equals(up.getIs_valid()) || !Boolean.TRUE.equals(down.getIs_valid())
                || !java.util.Objects.equals(up.getTenant_id(), down.getTenant_id())
                || !java.util.Objects.equals(up.getTenant_id(), tenant)) {
            throw new WcsException(ErrorCode.NOT_FOUND, "应用不存在或不可访问");
        }
        if (!Boolean.TRUE.equals(up.getEnabled()) || !Boolean.TRUE.equals(down.getEnabled())) {
            throw new WcsException(ErrorCode.FORBIDDEN, "上下游应用必须启用");
        }
        return contracts.requireMatch(up, down);
    }

    private static void pin(AppBindingEntity row, ProtocolContractService.Match match) {
        if (row.getRow_version()==null) row.setRow_version(0L);
        row.setUpstream_revision_id(match.upstreamSnapshotId());
        row.setDownstream_revision_id(match.downstreamSnapshotId());
        row.setContract_fingerprint(match.fingerprint());
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
