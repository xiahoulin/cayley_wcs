package com.cayleywcs.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * 协议数据格式 ↔ JSON 的编解码策略（需求 5 · Strategy）。
 * 把协议原始值规整为 JSON 友好类型（按 data_type：INT/UINT/WORD→long，REAL→double，其余→string）。
 */
public final class DataCodec {

    private DataCodec() {
    }

    /** 读：协议原始值 → JSON 值（Java 基本类型，Jackson 可直接序列化）。 */
    public static Object decode(String dataType, Object raw) {
        if (raw == null) {
            return null;
        }
        String type = dataType == null ? "" : dataType.toUpperCase();
        return switch (type) {
            case "INT", "UINT", "WORD", "DINT", "DWORD" -> toLong(raw);
            case "REAL", "LREAL", "FLOAT", "DOUBLE" -> toDouble(raw);
            case "BOOL" -> toBool(raw);
            default -> String.valueOf(raw);
        };
    }

    /** 写：JSON 值 → 协议可写值。 */
    public static Object encode(String dataType, Object jsonValue) {
        Object value = jsonValue instanceof JsonNode node ? unwrap(node) : jsonValue;
        return decode(dataType, value);
    }

    private static Object unwrap(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    private static long toLong(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return new BigDecimal(String.valueOf(raw).trim()).longValue();
    }

    private static double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(raw).trim());
    }

    private static boolean toBool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(raw).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }
}
