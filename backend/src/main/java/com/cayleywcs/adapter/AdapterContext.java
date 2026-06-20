package com.cayleywcs.adapter;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.List;
import java.util.Map;

/**
 * 适配器上下文：建连与读写所需的应用、协议、点位信息。屏蔽 ManagedConnection，让适配器只依赖配置数据。
 */
public record AdapterContext(
        ApplicationEntity application,
        ProtocolEntity protocol,
        List<ProtocolPointEntity> points
) {
    public String protocolType() {
        return protocol.getProtocol_type();
    }

    public Map<String, Object> connParams() {
        return application.getConn_params();
    }

    public String connParam(String field, String def) {
        Map<String, Object> params = application.getConn_params();
        if (params == null) {
            return def;
        }
        Object v = params.get(field);
        return v == null ? def : String.valueOf(v);
    }

    public long connParamLong(String field, long def) {
        Map<String, Object> params = application.getConn_params();
        if (params == null) {
            return def;
        }
        Object v = params.get(field);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? def : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
