package com.cayleywcs.faultcode.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.faultcode.FaultCodeResolver;
import com.cayleywcs.faultcode.FaultCodeService;
import com.cayleywcs.faultcode.entity.FaultCodeEntity;
import com.cayleywcs.faultcode.mapper.FaultCodeMapper;
import com.cayleywcs.system.CurrentUserProvider;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FaultCodeServiceImpl implements FaultCodeService {
    private final FaultCodeMapper faultCodeMapper;
    private final FaultCodeResolver faultCodeResolver;
    private final CurrentUserProvider currentUserProvider;

    public FaultCodeServiceImpl(FaultCodeMapper faultCodeMapper, FaultCodeResolver faultCodeResolver,
                                CurrentUserProvider currentUserProvider) {
        this.faultCodeMapper = faultCodeMapper;
        this.faultCodeResolver = faultCodeResolver;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public List<FaultCodeEntity> listByProtocol(Long protocolId) {
        return faultCodeMapper.selectList(new QueryWrapper<FaultCodeEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"protocol_id\"", protocolId)
                .orderByAsc("\"code\""));
    }

    @Override
    @Transactional
    public FaultCodeEntity create(FaultCodeEntity entity) {
        Audits.fillCreate(entity, currentUserProvider.currentUser());
        faultCodeMapper.insert(entity);
        faultCodeResolver.invalidate();
        return entity;
    }

    @Override
    @Transactional
    public FaultCodeEntity update(FaultCodeEntity entity) {
        FaultCodeEntity existing = faultCodeMapper.selectById(entity.getId());
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            throw new IllegalArgumentException("fault code not found: " + entity.getId());
        }
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        Audits.touch(entity);
        faultCodeMapper.updateById(entity);
        faultCodeResolver.invalidate();
        return faultCodeMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        FaultCodeEntity existing = faultCodeMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        boolean ok = faultCodeMapper.updateById(existing) == 1;
        faultCodeResolver.invalidate();
        return ok;
    }
}
