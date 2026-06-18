package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.adapter.AdapterContext;

/**
 * 由应用 conn_params 构造 PLC4X 连接串。优先使用显式的 plc4xConnectionString，否则按协议类型拼装。
 */
public final class Plc4xConnectionStrings {

    private Plc4xConnectionStrings() {
    }

    public static String build(String protocolType, AdapterContext ctx) {
        String explicit = ctx.connParam("plc4xConnectionString", "");
        if (!explicit.isBlank()) {
            return explicit;
        }
        return switch (protocolType) {
            case "opcua" -> opcua(ctx);
            case "modbus_tcp" -> modbus(ctx);
            case "s7" -> s7(ctx);
            default -> throw new IllegalArgumentException("unsupported plc4x type: " + protocolType);
        };
    }

    private static String opcua(AdapterContext ctx) {
        String endpoint = ctx.connParam("endpoint", "");
        if (!endpoint.isBlank()) {
            // opc.tcp://host:port/path  ->  opcua:tcp://host:port/path
            String rest = endpoint.startsWith("opc.tcp://") ? endpoint.substring("opc.tcp://".length()) : endpoint;
            return "opcua:tcp://" + rest + "?discovery=false";
        }
        String host = ctx.connParam("host", "127.0.0.1");
        long port = ctx.connParamLong("port", 4840);
        return "opcua:tcp://" + host + ":" + port + "?discovery=false";
    }

    private static String modbus(AdapterContext ctx) {
        String host = ctx.connParam("host", "127.0.0.1");
        long port = ctx.connParamLong("port", 502);
        long unitId = ctx.connParamLong("unitId", 1);
        return "modbus-tcp://" + host + ":" + port + "?unit-identifier=" + unitId;
    }

    private static String s7(AdapterContext ctx) {
        String host = ctx.connParam("host", "127.0.0.1");
        long rack = ctx.connParamLong("rack", 0);
        long slot = ctx.connParamLong("slot", 1);
        return "s7://" + host + "?remote-rack=" + rack + "&remote-slot=" + slot;
    }
}
