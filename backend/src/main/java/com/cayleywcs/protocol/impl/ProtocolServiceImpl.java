package com.cayleywcs.protocol.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cayleywcs.common.api.PageData;
import com.cayleywcs.common.api.PageSearch;
import com.cayleywcs.common.api.PageSupport;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.contract.DevicePointTemplates;
import com.cayleywcs.protocol.contract.PointContractValidator;
import com.cayleywcs.protocol.contract.impl.ProtocolContractServiceImpl;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.protocol.entity.ProtocolRevisionEntity;
import com.cayleywcs.protocol.mapper.ProtocolMapper;
import com.cayleywcs.protocol.mapper.ProtocolPointMapper;
import com.cayleywcs.protocol.mapper.ProtocolRevisionMapper;
import com.cayleywcs.system.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProtocolServiceImpl implements ProtocolService {
    private final ProtocolMapper protocolMapper;
    private final ProtocolPointMapper protocolPointMapper;
    private final ProtocolRevisionMapper revisionMapper;
    private final CurrentUserProvider currentUserProvider;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.application.ResourceDeletionService deletion;
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;

    public ProtocolServiceImpl(ProtocolMapper protocolMapper, ProtocolPointMapper protocolPointMapper,
                               CurrentUserProvider currentUserProvider, ProtocolRevisionMapper revisionMapper, JdbcTemplate jdbc) {
        this.protocolMapper = protocolMapper; this.protocolPointMapper = protocolPointMapper;
        this.currentUserProvider = currentUserProvider; this.revisionMapper = revisionMapper; this.jdbc = jdbc;
    }
    private Long tenant() { return currentUserProvider.currentUser().tenantId(); }
    private Long readTenant() {
        var user=currentUserProvider.systemUser();
        return user.userId()!=null && user.userId()==0L && "system".equals(user.userRole()) ? null : user.tenantId();
    }
    @Override public PageData<ProtocolEntity> page(PageSearch pageSearch) { return PageSupport.slice(listAll().stream().filter(row -> matches(row, pageSearch)).toList(), pageSearch); }
    @Override public List<ProtocolEntity> listAll() {
        return protocolMapper.selectList(new QueryWrapper<ProtocolEntity>().eq("\"is_valid\"", true).eq("\"tenant_id\"", tenant()).eq("\"legacy_template\"",false).ne("\"lifecycle_state\"", "retired")
                .inSql("\"app_id\"", "select id from wcs_application where is_valid=true").orderByDesc("\"id\""));
    }
    @Override public List<ProtocolEntity> listTemplates() {
        return protocolMapper.selectList(new QueryWrapper<ProtocolEntity>().eq("\"is_valid\"",true).eq("\"tenant_id\"",tenant()).eq("\"legacy_template\"",true).ne("\"lifecycle_state\"","retired").orderByDesc("\"id\""));
    }
    @Override public ProtocolEntity getById(Long id) {
        ProtocolEntity row = id == null ? null : protocolMapper.selectById(id);
        Long scope=readTenant();
        if (row == null || !Boolean.TRUE.equals(row.getIs_valid()) || scope!=null && !Objects.equals(row.getTenant_id(), scope)) throw new WcsException(404, "协议不存在");
        return row;
    }
    private ProtocolEntity lock(Long id) {
        lifecycle.references();
        ProtocolEntity row = protocolMapper.selectOne(new QueryWrapper<ProtocolEntity>().eq("\"id\"", id).eq("\"tenant_id\"", tenant()).eq("\"is_valid\"", true).last("FOR UPDATE"));
        if (row == null) throw new WcsException(404, "协议不存在"); return row;
    }
    private void owner(Long appId) {
        if(appId==null) throw new WcsException(400,"请先选择所属应用；新应用须先由 WCS 分配 AppID");
        if(jdbc.queryForList("select id from wcs_application where id=? and tenant_id=? and is_valid=true for update",appId,tenant()).isEmpty())
            throw new WcsException(404,"所属应用不存在或不可访问");
    }
    private void draft(ProtocolEntity row) {
        if(Boolean.TRUE.equals(row.getLegacy_template()))throw new WcsException(409,"历史模板仅供查阅，请先复制到所属应用");
        owner(row.getApp_id());
        if (!"draft".equals(row.getLifecycle_state())) throw new WcsException(409, "已发布或退役协议不能原地修改，请复制为草稿");
    }
    private void protocolDefinition(ProtocolEntity row, Long ignoreId) {
        if (row.getProtocol_code() == null || row.getProtocol_code().isBlank() || row.getProtocol_name() == null || row.getProtocol_name().isBlank()) throw new WcsException(400, "协议编码与名称必填");
        if (!com.cayleywcs.adapter.PointTypeRegistry.PROTOCOLS.contains(row.getProtocol_type())) throw new WcsException(4221, "未注册的协议类型");
        owner(row.getApp_id());
        QueryWrapper<ProtocolEntity> query = new QueryWrapper<ProtocolEntity>().eq("\"tenant_id\"", tenant()).eq("\"app_id\"",row.getApp_id()).eq("\"protocol_code\"", row.getProtocol_code()).eq("\"is_valid\"", true);
        if (ignoreId != null) query.ne("\"id\"", ignoreId);
        if (protocolMapper.selectCount(query) > 0) throw new WcsException(409, "同一应用下协议编码重复");
    }
    @Override @Transactional public ProtocolEntity create(ProtocolEntity entity) {
        lifecycle.references();
        protocolDefinition(entity, null); entity.setId(null); entity.setLegacy_template(false); entity.setLifecycle_state("draft"); entity.setPublished_revision_id(null); entity.setRow_version(0L);
        if (entity.getStatus() == null) entity.setStatus("enabled");
        if (entity.getData_format() == null) entity.setData_format(Map.of()); if (entity.getTransport_template() == null) entity.setTransport_template(Map.of());
        if (entity.getVersion() == null) entity.setVersion("1");
        Audits.fillCreate(entity, currentUserProvider.currentUser()); protocolMapper.insert(entity); return entity;
    }
    @Override @Transactional public ProtocolEntity update(ProtocolEntity entity) {
        ProtocolEntity old = lock(entity.getId()); draft(old); expected(old.getRow_version(), entity.getRow_version());
        if(!Objects.equals(old.getApp_id(),entity.getApp_id()))throw new WcsException(409,"协议所属应用不能修改，请复制到目标应用");
        entity.setLegacy_template(false); protocolDefinition(entity, entity.getId());
        entity.setCreator(old.getCreator()); entity.setCreate_time(old.getCreate_time()); entity.setTenant_id(old.getTenant_id()); entity.setIs_valid(true);
        entity.setLifecycle_state("draft"); entity.setPublished_revision_id(null); entity.setRow_version(old.getRow_version() + 1); Audits.touch(entity);
        protocolMapper.update(entity, new UpdateWrapper<ProtocolEntity>().eq("\"id\"", old.getId()).eq("\"row_version\"", old.getRow_version())); return protocolMapper.selectById(old.getId());
    }
    @Override public boolean delete(Long id) { return deletion.deleteProtocol(id); }
    @Override public List<ProtocolPointEntity> listPoints(Long protocolId) {
        getById(protocolId);
        QueryWrapper<ProtocolPointEntity> query=new QueryWrapper<ProtocolPointEntity>().eq("\"is_valid\"", true).eq("\"protocol_id\"", protocolId).orderByAsc("\"sort\"").orderByAsc("\"id\"");
        if(readTenant()!=null)query.eq("\"tenant_id\"",readTenant());
        return protocolPointMapper.selectList(query);
    }
    private void uniquePoint(ProtocolPointEntity row, Long ignoreId) {
        QueryWrapper<ProtocolPointEntity> query = new QueryWrapper<ProtocolPointEntity>().eq("\"protocol_id\"", row.getProtocol_id()).eq("\"tenant_id\"", tenant()).eq("\"is_valid\"", true).eq("\"active_identity\"", PointContractValidator.identity(row));
        if (ignoreId != null) query.ne("\"id\"", ignoreId);
        if (protocolPointMapper.selectCount(query) > 0) throw new WcsException(409, "同一模板、操作、方向的逻辑字段重复");
    }
    private ProtocolPointEntity point(Long id) {
        ProtocolPointEntity old = id == null ? null : protocolPointMapper.selectById(id);
        if (old == null || !Boolean.TRUE.equals(old.getIs_valid()) || !Objects.equals(old.getTenant_id(), tenant())) throw new WcsException(404, "协议点位不存在"); return old;
    }
    private void defaults(ProtocolPointEntity entity) {
        if (entity.getConstraints() == null) entity.setConstraints(Map.of()); if (entity.getScale() == null) entity.setScale(java.math.BigDecimal.ONE);
        if (entity.getSort() == null) entity.setSort(0L); if (entity.getTemplate_version() == null) entity.setTemplate_version(1L);
    }
    @Override @Transactional public ProtocolPointEntity createPoint(ProtocolPointEntity entity) {
        ProtocolEntity parent = lock(entity.getProtocol_id()); draft(parent); defaults(entity); PointContractValidator.definition(parent.getProtocol_type(), entity); uniquePoint(entity, null);
        entity.setId(null); entity.setRow_version(0L); entity.setActive_identity(PointContractValidator.identity(entity)); Audits.fillCreate(entity, currentUserProvider.currentUser());
        protocolPointMapper.insert(entity); touch(parent); return entity;
    }
    @Override @Transactional public ProtocolPointEntity updatePoint(ProtocolPointEntity entity) {
        ProtocolPointEntity old = point(entity.getId()); ProtocolEntity parent = lock(old.getProtocol_id()); draft(parent); expected(old.getRow_version(), entity.getRow_version());
        if (!Objects.equals(old.getProtocol_id(), entity.getProtocol_id())) throw new WcsException(409, "点位不能移动到其他协议，请复制新点位");
        defaults(entity); PointContractValidator.definition(parent.getProtocol_type(), entity); uniquePoint(entity, entity.getId());
        entity.setCreator(old.getCreator()); entity.setCreate_time(old.getCreate_time()); entity.setTenant_id(old.getTenant_id()); entity.setIs_valid(true);
        entity.setRow_version(old.getRow_version() + 1); entity.setActive_identity(PointContractValidator.identity(entity)); Audits.touch(entity);
        if (protocolPointMapper.update(entity, new UpdateWrapper<ProtocolPointEntity>().eq("\"id\"", old.getId()).eq("\"row_version\"", old.getRow_version())) != 1) throw new WcsException(409, "点位版本已变化，请刷新");
        touch(parent); return protocolPointMapper.selectById(entity.getId());
    }
    @Override @Transactional public boolean deletePoint(Long id) {
        return deletePoint(id, point(id).getRow_version());
    }
    @Override @Transactional public boolean deletePoint(Long id, Long rowVersion) {
        ProtocolPointEntity old = point(id); ProtocolEntity parent = lock(old.getProtocol_id()); draft(parent);
        old = point(id); expected(old.getRow_version(), rowVersion);
        old.setIs_valid(false); old.setRow_version(old.getRow_version() + 1); old.setActive_identity(null); Audits.touch(old);
        UpdateWrapper<ProtocolPointEntity> update = new UpdateWrapper<ProtocolPointEntity>().eq("\"id\"", id).eq("\"row_version\"", rowVersion).set("\"active_identity\"", null);
        boolean removed = protocolPointMapper.update(old, update) == 1; touch(parent); return removed;
    }
    private void touch(ProtocolEntity protocol) { protocol.setRow_version(protocol.getRow_version() + 1); Audits.touch(protocol); protocolMapper.updateById(protocol); }
    private static void expected(Long actual, Long requested) { if (requested == null || !Objects.equals(actual, requested)) throw new WcsException(409, "版本已变化，请刷新后重试并携带row_version"); }

    @Override @Transactional public ProtocolEntity cloneDraft(Long id) {
        return cloneDraft(id,null);
    }
    @Override @Transactional public ProtocolEntity cloneDraft(Long id, Long appId) {
        ProtocolEntity old = lock(id), copy = json.convertValue(old, ProtocolEntity.class);
        copy.setApp_id(appId==null?old.getApp_id():appId); copy.setLegacy_template(false);
        String code=old.getProtocol_code(); if(code.length()>110)code=code.substring(0,110);
        copy.setProtocol_code(code + "_DRAFT_" + java.util.UUID.randomUUID().toString().substring(0, 8)); copy.setProtocol_name(old.getProtocol_name() + "（草稿）");
        create(copy);
        for (ProtocolPointEntity point : listPoints(old.getId())) { ProtocolPointEntity cloned = json.convertValue(point, ProtocolPointEntity.class); cloned.setProtocol_id(copy.getId()); cloned.setId(null); cloned.setRow_version(0L); Audits.fillCreate(cloned, currentUserProvider.currentUser()); protocolPointMapper.insert(cloned); }
        jdbc.update("insert into wcs_fault_code(protocol_id,code,level,name,message,suggestion,creator,tenant_id) select ?,code,level,name,message,suggestion,creator,tenant_id from wcs_fault_code where protocol_id=? and tenant_id=? and is_valid=true",copy.getId(),old.getId(),tenant());
        return protocolMapper.selectById(copy.getId());
    }
    @Override @Transactional public ProtocolEntity publish(Long id, Long rowVersion) {
        ProtocolEntity parent = lock(id); draft(parent); expected(parent.getRow_version(), rowVersion);
        List<ProtocolPointEntity> points = listPoints(id); validatePublication(parent.getProtocol_type(), points);
        ProtocolRevisionEntity revision = new ProtocolRevisionEntity(); revision.setProtocol_id(id); revision.setRevision(parent.getRow_version() + 1); revision.setProtocol_type(parent.getProtocol_type());
        revision.setPoints(points.stream().sorted(Comparator.comparing(PointContractValidator::identity)).map(point -> json.convertValue(point, ProtocolPointEntity.class)).toList());
        try { revision.setFingerprint(ProtocolContractServiceImpl.hash(json.writeValueAsString(new java.util.TreeMap<>(Map.of("protocol_type", parent.getProtocol_type(), "points", revision.getPoints()))))); }
        catch (java.io.IOException ex) { throw new IllegalStateException("协议快照序列化失败", ex); }
        Audits.fillCreate(revision, currentUserProvider.currentUser()); revisionMapper.insert(revision);
        parent.setLifecycle_state("published"); parent.setPublished_revision_id(revision.getId()); parent.setRow_version(parent.getRow_version() + 1); Audits.touch(parent); protocolMapper.updateById(parent); return parent;
    }
    public static void validatePublication(String protocol, List<ProtocolPointEntity> points) {
        if (points.isEmpty()) throw new WcsException(4223, "空协议不能发布"); Set<String> identities = new HashSet<>(), templates = new HashSet<>();
        for (ProtocolPointEntity point : points) { PointContractValidator.definition(protocol, point); if (!identities.add(PointContractValidator.identity(point))) throw new WcsException(4223, "协议点位身份重复: " + PointContractValidator.identity(point)); templates.add(point.getTemplate_code()); }
        for (String template : templates) {
            List<ProtocolPointEntity> selected = points.stream().filter(point -> template.equals(point.getTemplate_code())).toList();
            for (String direction : List.of("request", "response")) {
                Set<String> fields = new HashSet<>();
                for (ProtocolPointEntity point : selected) if ("business".equals(point.getPoint_scope()) && direction.equals(point.getDirection())) {
                    if (!fields.add(point.getField_name())) throw new WcsException(4223, "业务点位在多个操作中重复: " + point.getField_name());
                    if (!Objects.equals(point.getOperation(), "request".equals(direction) ? "dispatch" : "result")) throw new WcsException(4223, "业务操作必须对应 dispatch/request 或 result/response");
                    if (!Boolean.TRUE.equals(point.getRequired())) throw new WcsException(4223, "设备模板业务点位必须完整必填: " + point.getField_name());
                }
                Set<String> expected = new HashSet<>("request".equals(direction) ? DevicePointTemplates.request(template) : DevicePointTemplates.response(template));
                if (template.equals("agv") && selected.stream().anyMatch(p->Long.valueOf(2).equals(p.getTemplate_version()) || Long.valueOf(3).equals(p.getTemplate_version()))) {
                    expected=direction.equals("response")?new HashSet<>(com.cayleywcs.adapter.seer.SeerPointCatalog.names()):new HashSet<>();
                    if(selected.stream().anyMatch(p->Long.valueOf(3).equals(p.getTemplate_version())))
                        expected.add(direction.equals("response")?com.cayleywcs.adapter.seer.SeerPointCatalog.NAV_SENT:com.cayleywcs.adapter.seer.SeerPointCatalog.NAV_STATION);
                }
                if (!fields.equals(expected)) { Set<String> missing = new HashSet<>(expected); missing.removeAll(fields); Set<String> extra = new HashSet<>(fields); extra.removeAll(expected); throw new WcsException(4223, template + "/" + direction + " 字段不完整，缺失=" + missing + "，多余=" + extra); }
            }
            if (!"http".equals(protocol)) DevicePointTemplates.internal(template).forEach(field -> {
                selected.stream().filter(row -> field.equals(row.getField_name()) && "internal".equals(row.getPoint_scope())).findFirst().orElseThrow(() -> new WcsException(4223, "缺少内部驱动点位: " + field));
            });
            DevicePointTemplates.validateHandlerContract(template,selected);
        }
    }
    @Override public List<ProtocolRevisionEntity> revisions(Long id) { getById(id); return revisionMapper.selectList(new QueryWrapper<ProtocolRevisionEntity>().eq("\"protocol_id\"", id).eq("\"tenant_id\"", tenant()).orderByDesc("\"revision\"")); }
    private static boolean matches(ProtocolEntity row, PageSearch search) {
        if (search.getSearchObjects() == null) return true;
        for (Map<String, Object> item : search.getSearchObjects()) {
            String name = String.valueOf(item.getOrDefault("name", "")); String needle = String.valueOf(item.getOrDefault("value", item.getOrDefault("text", ""))).trim();
            if("app_id".equals(name) && !needle.isBlank()) { if(!needle.equals(String.valueOf(row.getApp_id())))return false; else continue; }
            String haystack = switch (name) { case "protocol_code" -> row.getProtocol_code(); case "protocol_name" -> row.getProtocol_name(); case "target_system" -> row.getTarget_system(); case "protocol_type" -> row.getProtocol_type(); default -> ""; };
            if (!name.isBlank() && !needle.isBlank() && (haystack == null || !haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)))) return false;
        } return true;
    }
}
