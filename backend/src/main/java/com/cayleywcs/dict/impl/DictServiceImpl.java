package com.cayleywcs.dict.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.dict.DictService;
import com.cayleywcs.dict.entity.DictItemEntity;
import com.cayleywcs.dict.entity.DictTypeEntity;
import com.cayleywcs.dict.mapper.DictItemMapper;
import com.cayleywcs.dict.mapper.DictTypeMapper;
import com.cayleywcs.system.CurrentUserProvider;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DictServiceImpl implements DictService {
    private final DictTypeMapper dictTypeMapper;
    private final DictItemMapper dictItemMapper;
    private final CurrentUserProvider currentUserProvider;

    public DictServiceImpl(DictTypeMapper dictTypeMapper, DictItemMapper dictItemMapper,
                           CurrentUserProvider currentUserProvider) {
        this.dictTypeMapper = dictTypeMapper;
        this.dictItemMapper = dictItemMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public List<DictTypeEntity> listTypes() {
        return dictTypeMapper.selectList(new QueryWrapper<DictTypeEntity>()
                .eq("\"is_valid\"", true)
                .orderByAsc("\"sort\"")
                .orderByAsc("\"id\""));
    }

    @Override
    @Transactional
    public DictTypeEntity createType(DictTypeEntity entity) {
        Audits.fillCreate(entity, currentUserProvider.currentUser());
        dictTypeMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public DictTypeEntity updateType(DictTypeEntity entity) {
        DictTypeEntity existing = dictTypeMapper.selectById(entity.getId());
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            throw new IllegalArgumentException("dict type not found: " + entity.getId());
        }
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        Audits.touch(entity);
        dictTypeMapper.updateById(entity);
        return dictTypeMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean deleteType(Long id) {
        DictTypeEntity existing = dictTypeMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        return dictTypeMapper.updateById(existing) == 1;
    }

    @Override
    public List<DictItemEntity> listItems(String typeCode) {
        return dictItemMapper.selectList(new QueryWrapper<DictItemEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"type_code\"", typeCode)
                .orderByAsc("\"sort\"")
                .orderByAsc("\"id\""));
    }

    @Override
    @Transactional
    public DictItemEntity createItem(DictItemEntity entity) {
        Audits.fillCreate(entity, currentUserProvider.currentUser());
        dictItemMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public DictItemEntity updateItem(DictItemEntity entity) {
        DictItemEntity existing = dictItemMapper.selectById(entity.getId());
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            throw new IllegalArgumentException("dict item not found: " + entity.getId());
        }
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        Audits.touch(entity);
        dictItemMapper.updateById(entity);
        return dictItemMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean deleteItem(Long id) {
        DictItemEntity existing = dictItemMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        return dictItemMapper.updateById(existing) == 1;
    }
}
