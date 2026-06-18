package com.cayleywcs.protocol.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.protocol.mapper.ProtocolMapper;
import com.cayleywcs.protocol.mapper.ProtocolPointMapper;
import com.cayleywcs.system.CurrentUserProvider;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProtocolServiceImpl implements ProtocolService {
    private final ProtocolMapper protocolMapper;
    private final ProtocolPointMapper protocolPointMapper;
    private final CurrentUserProvider currentUserProvider;

    public ProtocolServiceImpl(ProtocolMapper protocolMapper, ProtocolPointMapper protocolPointMapper,
                               CurrentUserProvider currentUserProvider) {
        this.protocolMapper = protocolMapper;
        this.protocolPointMapper = protocolPointMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public PageData<ProtocolEntity> page(PageSearch pageSearch) {
        List<ProtocolEntity> rows = listAll().stream()
                .filter(row -> matches(row, pageSearch))
                .toList();
        return PageSupport.slice(rows, pageSearch);
    }

    @Override
    public List<ProtocolEntity> listAll() {
        return protocolMapper.selectList(new QueryWrapper<ProtocolEntity>()
                .eq("\"is_valid\"", true)
                .orderByDesc("\"id\""));
    }

    @Override
    public ProtocolEntity getById(Long id) {
        ProtocolEntity entity = protocolMapper.selectById(id);
        if (entity == null || !Boolean.TRUE.equals(entity.getIs_valid())) {
            throw new IllegalArgumentException("protocol not found: " + id);
        }
        return entity;
    }

    @Override
    @Transactional
    public ProtocolEntity create(ProtocolEntity entity) {
        Audits.fillCreate(entity, currentUserProvider.currentUser());
        protocolMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public ProtocolEntity update(ProtocolEntity entity) {
        ProtocolEntity existing = getById(entity.getId());
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        Audits.touch(entity);
        protocolMapper.updateById(entity);
        return protocolMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        ProtocolEntity existing = protocolMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        return protocolMapper.updateById(existing) == 1;
    }

    @Override
    public List<ProtocolPointEntity> listPoints(Long protocolId) {
        return protocolPointMapper.selectList(new QueryWrapper<ProtocolPointEntity>()
                .eq("\"is_valid\"", true)
                .eq("\"protocol_id\"", protocolId)
                .orderByAsc("\"sort\"")
                .orderByAsc("\"id\""));
    }

    @Override
    @Transactional
    public ProtocolPointEntity createPoint(ProtocolPointEntity entity) {
        Audits.fillCreate(entity, currentUserProvider.currentUser());
        protocolPointMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public ProtocolPointEntity updatePoint(ProtocolPointEntity entity) {
        ProtocolPointEntity existing = protocolPointMapper.selectById(entity.getId());
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            throw new IllegalArgumentException("protocol point not found: " + entity.getId());
        }
        entity.setCreator(existing.getCreator());
        entity.setCreate_time(existing.getCreate_time());
        entity.setTenant_id(existing.getTenant_id());
        entity.setIs_valid(true);
        Audits.touch(entity);
        protocolPointMapper.updateById(entity);
        return protocolPointMapper.selectById(entity.getId());
    }

    @Override
    @Transactional
    public boolean deletePoint(Long id) {
        ProtocolPointEntity existing = protocolPointMapper.selectById(id);
        if (existing == null || !Boolean.TRUE.equals(existing.getIs_valid())) {
            return false;
        }
        existing.setIs_valid(false);
        Audits.touch(existing);
        return protocolPointMapper.updateById(existing) == 1;
    }

    private static boolean matches(ProtocolEntity row, PageSearch pageSearch) {
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
                case "protocol_code" -> row.getProtocol_code();
                case "protocol_name" -> row.getProtocol_name();
                case "target_system" -> row.getTarget_system();
                case "protocol_type" -> row.getProtocol_type();
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
