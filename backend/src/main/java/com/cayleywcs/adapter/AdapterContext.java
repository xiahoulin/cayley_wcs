package com.cayleywcs.adapter;

import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

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

    public JsonNode connParams() {
        return application.getConn_params();
    }

    public String connParam(String field, String def) {
        JsonNode params = application.getConn_params();
        if (params == null || params.get(field) == null || params.get(field).isNull()) {
            return def;
        }
        return params.get(field).asText();
    }

    public long connParamLong(String field, long def) {
        JsonNode params = application.getConn_params();
        if (params == null || params.get(field) == null || !params.get(field).isNumber()) {
            return def;
        }
        return params.get(field).asLong();
    }
}
