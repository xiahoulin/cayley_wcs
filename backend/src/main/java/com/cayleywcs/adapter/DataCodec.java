package com.cayleywcs.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 协议数据格式 ↔ JSON 的编解码策略（需求 5 · Strategy）。
 * 把协议原始值规整为 JSON 友好类型（按 data_type：INT/UINT/WORD→long，REAL→double，BOOL→boolean，
 * ARRAY[...]→List，其余→string）。
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
        if (type.startsWith("ARRAY")) {
            return decodeArray(raw);
        }
        return switch (type) {
            case "INT", "UINT", "WORD", "DINT", "DWORD" -> toLong(raw);
            case "REAL", "LREAL", "FLOAT", "DOUBLE" -> toDouble(raw);
            case "BOOL" -> toBool(raw);
            default -> String.valueOf(raw);
        };
    }

    /** 数组解码：遍历元素逐个转 long（ARRAY OF INT 场景，如故障码数组 DBW64-DBW182）。 */
    private static List<Long> decodeArray(Object raw) {
        if (raw instanceof Collection<?> coll) {
            List<Long> list = new ArrayList<>(coll.size());
            for (Object elem : coll) {
                list.add(toLong(elem));
            }
            return list;
        }
        if (raw instanceof Object[] arr) {
            List<Long> list = new ArrayList<>(arr.length);
            for (Object elem : arr) {
                list.add(toLong(elem));
            }
            return list;
        }
        // 兜底：标量包装成单元素列表（仿真器场景）
        List<Long> single = new ArrayList<>(1);
        single.add(toLong(raw));
        return single;
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
