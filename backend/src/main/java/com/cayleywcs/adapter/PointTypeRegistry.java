package com.cayleywcs.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict protocol-aware primitive and typed-array codecs. No guessed type or lossy coercion. */
public final class PointTypeRegistry {
    public static final List<String> PROTOCOLS = List.of("http", "opcua", "modbus_tcp", "s7", "mqtt", "tcp", "sim", "loopback");
    private static final Pattern ARRAY = Pattern.compile("ARRAY\\s*\\[([^]]+)]\\s*OF\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_ARRAY = Pattern.compile("ARRAY\\s*[<:]\\s*(.+?)>?$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NUMERIC = Set.of("INT8", "UINT8", "INT16", "UINT16", "INT32", "UINT32", "INT64", "UINT64", "FLOAT32", "FLOAT64", "DECIMAL");
    private static final BigInteger JSON_SAFE = BigInteger.valueOf(9007199254740991L);
    private PointTypeRegistry() { }

    public static String canonical(String protocol, String nativeType) {
        protocol(protocol);
        if (nativeType == null || nativeType.isBlank()) throw invalid("未指定原生类型");
        String type = nativeType.trim().toUpperCase(Locale.ROOT);
        Matcher array = ARRAY.matcher(type);
        if (array.matches()) { dimensions(array.group(1)); return "ARRAY<" + canonical(protocol, array.group(2)) + ">"; }
        Matcher generic = GENERIC_ARRAY.matcher(type);
        if (generic.matches()) return "ARRAY<" + canonical(protocol, generic.group(1)) + ">";
        if (type.endsWith("[]")) return "ARRAY<" + canonical(protocol, type.substring(0, type.length() - 2)) + ">";
        String result = switch (type) {
            case "BOOL", "BOOLEAN", "BIT" -> "BOOL";
            case "TEXT", "STRING", "CHARACTERSTRING", "WSTRING" -> "STRING";
            case "SINT", "SBYTE", "INT8" -> "INT8";
            case "BYTE", "USINT", "UINT8" -> "UINT8";
            case "INT", "SHORT", "INT16" -> "INT16";
            case "UINT", "USHORT", "WORD", "UINT16" -> "UINT16";
            case "DINT", "INT32" -> "INT32";
            case "UDINT", "DWORD", "UINT32" -> "UINT32";
            case "LINT", "LONG", "INT64", "INTEGER" -> "INT64";
            case "ULINT", "ULONG", "LWORD", "UINT64" -> "UINT64";
            case "REAL", "FLOAT", "FLOAT32" -> "FLOAT32";
            case "LREAL", "DOUBLE", "FLOAT64", "NUMBER" -> "FLOAT64";
            case "DECIMAL" -> "DECIMAL";
            default -> throw invalid("未知原生类型: " + nativeType);
        };
        if ("modbus_tcp".equals(protocol) && Set.of("STRING", "DECIMAL").contains(result)) throw invalid("Modbus 未定义该类型的寄存器编码: " + nativeType);
        if ("opcua".equals(protocol) && "UINT64".equals(result)) throw invalid("当前PLC4X OPC UA驱动不能无损读写UInt64，禁止发布或发送");
        if (Set.of("opcua", "s7").contains(protocol) && "DECIMAL".equals(result)) throw invalid("协议不支持 DECIMAL 原生类型: " + protocol);
        return result;
    }

    public static Object encode(String protocol, String nativeType, Object value) { canonical(protocol, nativeType); return convert(protocol, nativeType, unwrap(value), false); }
    public static Object decode(String protocol, String nativeType, Object value) { canonical(protocol, nativeType); return convert(protocol, nativeType, unwrap(value), true); }

    private static Object convert(String protocol, String nativeType, Object value, boolean reading) {
        if (value == null) return null;
        String logical = canonical(protocol, nativeType);
        if (logical.startsWith("ARRAY<")) {
            List<Object> values = sequence(value);
            Matcher declared = ARRAY.matcher(nativeType.trim());
            int[] dims = declared.matches() ? dimensions(declared.group(1)) : new int[0];
            String childType = declared.matches() ? declared.group(2) : logical.substring(6, logical.length() - 1);
            if (dims.length > 1) return arrayDimension(protocol, childType, values, dims, 0, reading);
            if (dims.length == 1 && values.size() != dims[0]) throw invalid("数组长度要求 " + dims[0] + "，实际 " + values.size());
            List<Object> result = new ArrayList<>(values.size());
            for (Object item : values) { if (item == null) throw invalid("数组元素不允许为空"); result.add(convert(protocol, childType, item, reading)); }
            return result;
        }
        if ("BOOL".equals(logical)) { if (!(value instanceof Boolean)) throw invalid("布尔类型要求 true 或 false"); return value; }
        if ("STRING".equals(logical)) { if (!(value instanceof String)) throw invalid("字符串类型不接受数值或对象"); return value; }
        if (!NUMERIC.contains(logical)) throw invalid("类型无法编码: " + logical);
        if (logical.startsWith("INT") || logical.startsWith("UINT")) {
            if (!(value instanceof Number)) throw invalid("整数类型要求 JSON 整数，十进制字符串需显式 wire_encoding");
            boolean signed = logical.startsWith("INT"); int bits = Integer.parseInt(logical.substring(signed ? 3 : 4));
            BigInteger low = signed ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO;
            BigInteger high = BigInteger.ONE.shiftLeft(signed ? bits - 1 : bits).subtract(BigInteger.ONE);
            BigDecimal decimal=number(value);
            if (decimal.compareTo(new BigDecimal(low)) < 0 || decimal.compareTo(new BigDecimal(high)) > 0) throw invalid(logical + " 越界");
            BigInteger integer;
            try { integer = decimal.toBigIntegerExact(); } catch (ArithmeticException ex) { throw invalid("整数类型不接受小数"); }
            if (reading) { if (logical.equals("UINT64") && integer.bitLength() > 63) return integer; return integer.longValueExact(); }
            return switch (logical) {
                case "INT8" -> integer.byteValueExact(); case "INT16" -> integer.shortValueExact();
                case "UINT8", "UINT16", "INT32" -> integer.intValueExact(); case "UINT32", "INT64" -> integer.longValueExact();
                case "UINT64" -> integer; default -> throw invalid("整数类型无法编码");
            };
        }
        if (!(value instanceof Number)) throw invalid("数值类型要求 JSON 数字");
        BigDecimal decimal = number(value); if ("DECIMAL".equals(logical)) return decimal;
        if ("FLOAT32".equals(logical)) { float result = decimal.floatValue(); if (!Float.isFinite(result) || (result == 0 && decimal.signum() != 0)) throw invalid("FLOAT32 上溢或下溢"); return reading ? (double) result : (Object) result; }
        double result = decimal.doubleValue(); if (!Double.isFinite(result) || (result == 0 && decimal.signum() != 0)) throw invalid("FLOAT64 上溢或下溢"); return result;
    }

    private static List<Object> arrayDimension(String protocol, String element, List<Object> values, int[] dims, int level, boolean reading) {
        if (values.size() != dims[level]) throw invalid("数组第 " + (level + 1) + " 维长度要求 " + dims[level]);
        List<Object> result = new ArrayList<>();
        for (Object item : values) result.add(level == dims.length - 1 ? convert(protocol, element, item, reading) : arrayDimension(protocol, element, sequence(item), dims, level + 1, reading));
        return result;
    }
    private static int[] dimensions(String declaration) {
        String[] parts = declaration.split(","); int[] dimensions = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] range = parts[i].trim().split("\\.\\.");
            try { dimensions[i] = range.length == 2 ? Math.addExact(Math.subtractExact(Integer.parseInt(range[1]), Integer.parseInt(range[0])), 1) : Integer.parseInt(range[0]); }
            catch (NumberFormatException | ArithmeticException ex) { throw invalid("数组声明的维度无效"); }
            if (dimensions[i] < 1 || dimensions[i] > 65536) throw invalid("数组声明的长度无效");
        }
        return dimensions;
    }
    public static BigDecimal number(Object value) {
        if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f)) throw invalid("不允许非有限数值");
        try { return value instanceof BigDecimal d ? d : new BigDecimal(value.toString()); } catch (RuntimeException ex) { throw invalid("不是合法数值"); }
    }
    public static List<Object> sequence(Object value) {
        if (value instanceof Collection<?> list) return new ArrayList<>(list);
        if (value != null && value.getClass().isArray()) { List<Object> result = new ArrayList<>(); for (int i = 0; i < Array.getLength(value); i++) result.add(Array.get(value, i)); return result; }
        throw invalid("数组类型要求数组，不能将标量自动包装");
    }
    public static List<Integer> arrayDimensions(String nativeType) {
        Matcher declared = ARRAY.matcher(nativeType.trim());
        if (!declared.matches()) return List.of();
        return java.util.Arrays.stream(dimensions(declared.group(1))).boxed().toList();
    }
    private static Object unwrap(Object value) {
        if (!(value instanceof JsonNode node)) return value;
        if (node.isNull()) return null; if (node.isNumber()) return node.numberValue(); if (node.isBoolean()) return node.booleanValue(); if (node.isTextual()) return node.textValue();
        if (node.isArray()) { List<Object> result = new ArrayList<>(); node.forEach(item -> result.add(unwrap(item))); return result; }
        throw invalid("点位值不支持未定义的对象类型");
    }
    public static boolean compatible(String sourceProtocol, String sourceType, String targetProtocol, String targetType) {
        try { return canonical(sourceProtocol, sourceType).equals(canonical(targetProtocol, targetType)); } catch (IllegalArgumentException ex) { return false; }
    }
    public static List<Map<String, Object>> pairMatrix(String sourceType, String targetType) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String source : PROTOCOLS) for (String target : PROTOCOLS) { Map<String, Object> row = new LinkedHashMap<>(); row.put("source_protocol", source); row.put("target_protocol", target); row.put("compatible", compatible(source, sourceType, target, targetType)); result.add(row); }
        return List.copyOf(result);
    }
    public static List<String> options(String protocol) {
        protocol(protocol);
        return List.of("BOOL", "STRING", "INT8", "UINT8", "INT16", "UINT16", "INT32", "UINT32", "INT64", "UINT64", "FLOAT32", "FLOAT64", "DECIMAL").stream()
                .filter(type -> { try { canonical(protocol, type); return true; } catch (IllegalArgumentException ex) { return false; } }).toList();
    }
    private static void protocol(String value) { if (!PROTOCOLS.contains(value)) throw invalid("未注册协议类型: " + value); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
