package com.cayleywcs.protocol.contract.impl;

import com.cayleywcs.adapter.PointTypeRegistry;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.protocol.contract.DevicePointTemplates;
import com.cayleywcs.protocol.contract.PointContractValidator;
import com.cayleywcs.protocol.contract.ProtocolContractService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.cayleywcs.protocol.entity.ProtocolRevisionEntity;
import com.cayleywcs.protocol.mapper.ProtocolMapper;
import com.cayleywcs.protocol.mapper.ProtocolRevisionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

@Service
public class ProtocolContractServiceImpl implements ProtocolContractService {
    private final ProtocolMapper protocols;
    private final ProtocolRevisionMapper revisions;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    public ProtocolContractServiceImpl(ProtocolMapper protocols, ProtocolRevisionMapper revisions) { this.protocols = protocols; this.revisions = revisions; }

    @Override public Snapshot resolve(ApplicationEntity app) {
        if (app == null) throw new WcsException(4223, "应用不存在");
        return resolve(app, "upstream".equals(app.getDirection()) && "none".equals(app.getDevice_type()) ? "*" : DevicePointTemplates.template(app.getDevice_type()));
    }
    @Override public Snapshot resolve(ApplicationEntity app, String templateCode) {
        if (app == null || !Boolean.TRUE.equals(app.getIs_valid()) || !Boolean.TRUE.equals(app.getEnabled())) throw new WcsException(4223, "应用无效或已停用");
        if(app.getId()==null || app.getProtocol_id()==null)throw new WcsException(4223,"应用须先取得 AppID 并配置本应用的已发布协议");
        ProtocolEntity protocol = protocols.selectById(app.getProtocol_id());
        if (protocol == null || !Boolean.TRUE.equals(protocol.getIs_valid()) || !Objects.equals(protocol.getApp_id(),app.getId()) || Boolean.TRUE.equals(protocol.getLegacy_template()) || !Objects.equals(protocol.getTenant_id(), app.getTenant_id()) || !"published".equals(protocol.getLifecycle_state()) || !"enabled".equals(protocol.getStatus())) throw new WcsException(4223, "应用协议未发布、已退役或所属应用不匹配（归属校验失败）");
        if (app.getId()!=null && app.getProtocol_revision_id()==null) throw new WcsException(4223,"现有应用尚未显式选择已发布协议版本");
        Long revisionId = app.getProtocol_revision_id() != null ? app.getProtocol_revision_id() : protocol.getPublished_revision_id();
        Snapshot snapshot = resolveRevision(revisionId, templateCode);
        ProtocolRevisionEntity revision = revisions.selectById(revisionId);
        if (!Objects.equals(snapshot.protocolId(), app.getProtocol_id()) || !Objects.equals(revision.getTenant_id(), app.getTenant_id())) throw new WcsException(4223, "应用选定版本与协议或归属不匹配");
        com.cayleywcs.adapter.ApplicationAdapterCatalog.validate(app, snapshot.protocolType());
        return snapshot;
    }
    @Override public Snapshot resolveRevision(Long revisionId, String templateCode) {
        if (revisionId == null) throw new WcsException(4223, "应用没有选定已发布协议版本");
        ProtocolRevisionEntity revision = revisions.selectById(revisionId);
        if (revision == null || !Boolean.TRUE.equals(revision.getIs_valid())) throw new WcsException(4223, "已发布协议版本不存在");
        String code = DevicePointTemplates.template(templateCode);
        List<ProtocolPointEntity> points = revision.getPoints().stream().filter(point -> "*".equals(code) || code.equals(point.getTemplate_code())).map(this::copy).toList();
        if (points.isEmpty()) throw new WcsException(4223, "协议版本不支持设备模板: " + code);
        com.cayleywcs.protocol.impl.ProtocolServiceImpl.validatePublication(revision.getProtocol_type(),points);
        return new Snapshot(revision.getId(), revision.getProtocol_id(), revision.getRevision(), revision.getProtocol_type(), code, List.copyOf(points), revision.getFingerprint());
    }
    @Override public Map<String, Object> validatePayload(ApplicationEntity app, Map<String, Object> payload) { return validatePayload(resolve(app), payload); }
    @Override public Map<String, Object> validatePayload(Snapshot snapshot, Map<String, Object> payload) { return PointContractValidator.payload(snapshot.protocolType(), snapshot.templateCode(), snapshot.points(), payload, "request"); }
    @Override public Map<String, Object> validateResponse(ApplicationEntity app, Map<String, Object> payload) { return validateResponse(resolve(app), payload); }
    @Override public Map<String, Object> validateResponse(Snapshot snapshot, Map<String, Object> payload) { return PointContractValidator.payload(snapshot.protocolType(), snapshot.templateCode(), snapshot.points(), payload, "response"); }

    @Override public Match match(ApplicationEntity upstream, ApplicationEntity downstream) {
        List<Difference> differences = new ArrayList<>();
        if (upstream == null || downstream == null) return failed("应用", "有效应用", "缺失", "应用不存在");
        if (!Objects.equals(upstream.getTenant_id(), downstream.getTenant_id())) return failed("归属", "同一固定归属", "不同", "应用固定归属不同");
        if (!"upstream".equals(upstream.getDirection()) || !"downstream".equals(downstream.getDirection())) return failed("direction", "upstream → downstream", upstream.getDirection() + " → " + downstream.getDirection(), "应用方向不支持该关系");
        if (!DevicePointTemplates.CODES.contains(DevicePointTemplates.template(downstream.getDevice_type()))) return failed("device_type", "已实现设备", downstream.getDevice_type(), "设备处理器未实现");
        Snapshot source, target;
        try { source = resolve(upstream, DevicePointTemplates.template(downstream.getDevice_type())); target = resolve(downstream); }
        catch (WcsException ex) { return failed("protocol", "已发布且完整的模板", "不可用", ex.getMessage()); }
        if (!"http".equals(source.protocolType())) differences.add(new Difference("protocol_type", "request", "http", source.protocolType(), "当前上位入站仅实现HTTP，类型通过不能替代收发入口"));
        differences.addAll(compare(source, target));
        String fingerprint = hash(source.id() + ":" + target.id() + ":" + source.templateCode() + ":" + source.fingerprint() + ":" + target.fingerprint());
        return new Match(differences.isEmpty(), fingerprint, source.id(), target.id(), List.copyOf(differences));
    }
    @Override public Match requireMatch(ApplicationEntity upstream, ApplicationEntity downstream) {
        Match result = match(upstream, downstream);
        if (!result.compatible()) throw new WcsException(4223, "点位约定不匹配: " + result.differences().stream().map(diff -> diff.field() + "(" + diff.direction() + "): " + diff.reason()).limit(12).toList());
        return result;
    }

    public static List<Difference> compare(Snapshot source, Snapshot target) {
        List<Difference> differences = new ArrayList<>();
        Map<String, ProtocolPointEntity> left = business(source.points()), right = business(target.points());
        Set<String> union = new java.util.TreeSet<>(left.keySet()); union.addAll(right.keySet());
        for (String key : union) {
            ProtocolPointEntity a = left.get(key), b = right.get(key);
            if (a == null || b == null) { ProtocolPointEntity present = a == null ? b : a; differences.add(new Difference(present.getField_name(), present.getDirection(), a == null ? "缺少字段" : "存在字段", b == null ? "缺少字段" : "存在字段", "请求/响应字段集合不完全一致")); continue; }
            String reason = null;
            try {
                PointContractValidator.definition(source.protocolType(), a); PointContractValidator.definition(target.protocolType(), b);
                if (!PointTypeRegistry.canonical("http", a.getLogical_type()).equals(PointTypeRegistry.canonical("http", b.getLogical_type()))) reason = "逻辑类型不同";
            } catch (WcsException ex) { reason = ex.getMessage(); }
            if (!Objects.equals(a.getTemplate_version(), b.getTemplate_version())) reason = "模板版本不同";
            if (!Objects.equals(a.getRequired(), b.getRequired()) || !Objects.equals(a.getNullable(), b.getNullable())) reason = "必填或空值规则不同";
            if (!PointContractValidator.equivalent(a.getDefault_value(), b.getDefault_value())) reason = "默认值不同";
            if (!PointContractValidator.equivalent(PointContractValidator.businessConstraints(a), PointContractValidator.businessConstraints(b))) reason = "值域、完整枚举、数组、单位、编码或其他约束不同";
            if (reason != null) differences.add(new Difference(a.getField_name(), a.getDirection(), a.getLogical_type(), b.getLogical_type(), reason));
        }
        return List.copyOf(differences);
    }
    private static Map<String, ProtocolPointEntity> business(List<ProtocolPointEntity> points) {
        Map<String, ProtocolPointEntity> result = new TreeMap<>();
        for (ProtocolPointEntity point : points) if ("business".equals(point.getPoint_scope())) {
            String key = point.getOperation() + "/" + point.getDirection() + "/" + point.getField_name();
            if (result.put(key, point) != null) throw new WcsException(4223, "协议业务点位重复: " + key);
        }
        return result;
    }
    private static Match failed(String field, String expected, String actual, String reason) { return new Match(false, "", null, null, List.of(new Difference(field, "", expected, actual, reason))); }
    private ProtocolPointEntity copy(ProtocolPointEntity point) { return json.convertValue(point, ProtocolPointEntity.class); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
