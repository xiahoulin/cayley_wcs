package com.cayleywcs.protocol.contract;

import com.cayleywcs.adapter.PointTypeRegistry;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure validation shared by publication, bindings and request/response admission. */
public final class PointContractValidator {
    private static final Set<String> CONSTRAINT_KEYS = Set.of("min", "max", "enum", "length", "min_length", "max_length", "max_items", "dimensions", "element_type", "encoding", "unit", "wire_encoding", "reserved_values", "precision", "scale", "byte_order", "word_order");
    private PointContractValidator() { }

    public static void definition(String protocol, ProtocolPointEntity point) {
        if (point.getField_name() == null || !point.getField_name().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) fail(4223, point, "字段编码无效");
        if (point.getTemplate_code() == null || !DevicePointTemplates.CODES.contains(point.getTemplate_code())) fail(4223, point, "设备模板未实现");
        if (point.getTemplate_version() == null || point.getTemplate_version() < 1) fail(4223, point, "模板版本必须为正整数");
        if (point.getDirection() == null || !Set.of("request", "response").contains(point.getDirection())) fail(4223, point, "方向必须为 request 或 response");
        if (point.getPoint_scope() == null || !Set.of("business", "internal").contains(point.getPoint_scope())) fail(4223, point, "用途必须为 business 或 internal");
        if (point.getOperation() == null || point.getOperation().isBlank()) fail(4223, point, "操作必填");
        if (point.getAddress() == null || point.getAddress().isBlank()) fail(4223, point, "原生映射地址必填");
        if (point.getRequired() == null || point.getNullable() == null) fail(4223, point, "必填与空值规则必须明确");
        String nativeType;
        String logical;
        try { nativeType = PointTypeRegistry.canonical(protocol, point.getData_type()); logical = PointTypeRegistry.canonical("http", point.getLogical_type()); }
        catch (IllegalArgumentException ex) { throw new WcsException(4224, point.getField_name() + ": " + ex.getMessage()); }
        Map<String, Object> constraints = constraints(point);
        Set<String> unknown = new HashSet<>(constraints.keySet()); unknown.removeAll(CONSTRAINT_KEYS);
        if (!unknown.isEmpty()) fail(4223, point, "未知约束: " + unknown);
        for (String key : List.of("byte_order", "word_order")) if (constraints.containsKey(key)) {
            if (!"modbus_tcp".equals(protocol) || !Set.of("BIG_ENDIAN", "LITTLE_ENDIAN").contains(String.valueOf(constraints.get(key)))) fail(4223, point, key + " 仅允许Modbus的BIG_ENDIAN或LITTLE_ENDIAN原生映射");
        }
        nativeMapping(protocol, point, nativeType, constraints);
        if (!nativeType.equals(logical) && !integerCompatible(logical, nativeType, constraints)) fail(4224, point, "逻辑类型 " + logical + " 与原生类型 " + nativeType + " 不能无损映射");
        if (point.getScale() != null && point.getScale().compareTo(BigDecimal.ONE) != 0) fail(4224, point, "当前驱动未实现可逆缩放，发布要求 scale=1");
        if (constraints.containsKey("min") && constraints.containsKey("max") && decimal(constraints.get("min")).compareTo(decimal(constraints.get("max"))) > 0) fail(4223, point, "最小值超过最大值");
        for (String key : List.of("min", "max")) if (constraints.containsKey(key)) {
            if (!logical.startsWith("ARRAY") && !logical.equals("STRING") && !logical.equals("BOOL")) {
                try { PointTypeRegistry.encode(protocol, point.getData_type(), decimal(constraints.get(key))); }
                catch (RuntimeException ex) { fail(4224, point, "约束 " + key + " 超出原生类型范围"); }
            } else fail(4223, point, "该类型不能使用数值范围");
        }
        if (logical.startsWith("ARRAY") && !constraints.containsKey("dimensions") && !constraints.containsKey("length") && !constraints.containsKey("max_items")) fail(4223, point, "数组必须声明维度或长度上限");
        if (logical.startsWith("ARRAY")) {
            if (constraints.containsKey("dimensions")) {
                if (!(constraints.get("dimensions") instanceof List<?> dims) || dims.isEmpty()) fail(4223,point,"数组维度必须是非空整数数组");
                else for(Object size:dims) { try { if(decimal(size).intValueExact()<1)fail(4223,point,"数组维度长度必须为正整数"); }catch(ArithmeticException ex){fail(4223,point,"数组维度长度必须为整数");} }
            }
            if (constraints.get("element_type") instanceof String element && !PointTypeRegistry.canonical("http",element).equals(logical.substring(6,logical.length()-1))) fail(4223,point,"数组元素类型与逻辑类型不同");
            List<Integer> declared = PointTypeRegistry.arrayDimensions(point.getData_type());
            if (!declared.isEmpty()) {
                if (constraints.get("dimensions") instanceof List<?> dims) {
                    if (!PointContractValidator.equivalent(declared,dims)) fail(4223,point,"原生数组声明与逻辑维度不同");
                } else if (declared.size()!=1 || !constraints.containsKey("length") || decimal(constraints.get("length")).intValueExact()!=declared.getFirst()) fail(4223,point,"固定原生数组必须定义相同的固定逻辑长度");
            }
        }
        if (logical.equals("STRING") && (!constraints.containsKey("encoding") || !constraints.containsKey("max_length"))) fail(4223, point, "字符串必须声明编码和最大长度");
        if (logical.equals("STRING")) {
            try { java.nio.charset.Charset.forName(String.valueOf(constraints.get("encoding"))); }
            catch(IllegalArgumentException ex) { fail(4223,point,"字符串编码未实现"); }
        }
        if ((logical.equals("INT64") || logical.equals("UINT64")) && !"decimal_string".equals(constraints.get("wire_encoding"))) {
            BigDecimal low = constraints.containsKey("min") ? decimal(constraints.get("min")) : new BigDecimal("-9223372036854775808");
            BigDecimal high = constraints.containsKey("max") ? decimal(constraints.get("max")) : new BigDecimal("18446744073709551615");
            if (low.abs().max(high.abs()).compareTo(new BigDecimal("9007199254740991")) > 0) fail(4223, point, "64位值域超过JSON安全整数时必须声明 decimal_string");
        }
        for (String key : List.of("length", "min_length", "max_length", "max_items", "precision", "scale")) if (constraints.containsKey(key)) {
            try { if (decimal(constraints.get(key)).intValueExact() < 0) fail(4223, point, key + " 不能为负数"); } catch (ArithmeticException ex) { fail(4223, point, key + " 必须为整数"); }
        }
        if (constraints.containsKey("wire_encoding") && (!"decimal_string".equals(constraints.get("wire_encoding")) || !Set.of("INT64", "UINT64").contains(logical))) fail(4223, point, "wire_encoding仅支持64位整数的decimal_string");
        if (point.getDefault_value() != null) value(protocol, point, point.getDefault_value(), 4224);
        Object choices = constraints.get("enum");
        if (choices != null) { if (!(choices instanceof Collection<?> list) || list.isEmpty()) fail(4223, point, "枚举必须是非空数组"); else for (Object item : list) value(protocol, point, item, 4224); }
    }

    public static Map<String, Object> payload(String protocol, String template, List<ProtocolPointEntity> points, Map<String, Object> payload, String direction) {
        int code = "response".equals(direction) ? 4225 : 4241;
        if (payload == null) throw new WcsException(code, "payload 必须为点位对象");
        Map<String, ProtocolPointEntity> allowed = new LinkedHashMap<>();
        for (ProtocolPointEntity point : points) if ("business".equals(point.getPoint_scope()) && direction.equals(point.getDirection())) {
            if (allowed.put(point.getField_name(), point) != null) fail(4223, point, "同一业务方向字段重复");
        }
        if (allowed.isEmpty()) throw new WcsException(4223, "所选模板没有 " + direction + " 业务点位");
        for (String field : payload.keySet()) if (!allowed.containsKey(field)) throw new WcsException(code, "payload." + field + ": 未定义或禁止访问的点位");
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (ProtocolPointEntity point : allowed.values()) {
            Object raw = payload.get(point.getField_name());
            if (!payload.containsKey(point.getField_name())) {
                if (point.getDefault_value() != null && "request".equals(direction)) raw = point.getDefault_value();
                else { if (Boolean.TRUE.equals(point.getRequired())) fail(code, point, "必填点位缺失"); else continue; }
            }
            normalized.put(point.getField_name(), value(protocol, point, raw, code));
        }
        if ("request".equals(direction)) semantic(template, normalized);
        return normalized;
    }

    public static Object value(String protocol, ProtocolPointEntity point, Object raw, int errorCode) {
        if (raw == null) { if (!Boolean.TRUE.equals(point.getNullable())) fail(errorCode, point, "不允许空值"); return null; }
        Map<String, Object> rules = constraints(point);
        Object number = raw;
        Object wireValue = raw;
        if ("decimal_string".equals(rules.get("wire_encoding"))) {
            if (errorCode == 4225 && raw instanceof Number) wireValue = PointTypeRegistry.number(raw).toBigIntegerExact().toString();
            if (!(wireValue instanceof String s) || !s.matches("-?(0|[1-9][0-9]*)")) fail(errorCode, point, "无损整数必须使用十进制字符串");
            number = new BigInteger((String) wireValue);
        }
        Object logicalValue;
        try {
            String logicalWireType=PointTypeRegistry.canonical("http",point.getLogical_type());
            if (logicalWireType.startsWith("ARRAY<") && rules.get("dimensions") instanceof List<?> dims && dims.size()>1) {
                logicalWireType="ARRAY["+String.join(",",dims.stream().map(Object::toString).toList())+"] OF "+logicalWireType.substring(6,logicalWireType.length()-1);
            }
            logicalValue = PointTypeRegistry.encode("http", logicalWireType, number);
            PointTypeRegistry.encode(protocol, point.getData_type(), number);
        } catch (RuntimeException ex) { throw new WcsException(errorCode, "payload." + point.getField_name() + ": " + ex.getMessage()); }
        if (number instanceof Number) {
            BigDecimal val = PointTypeRegistry.number(number);
            if (rules.containsKey("min") && val.compareTo(decimal(rules.get("min"))) < 0) fail(errorCode, point, "低于最小值 " + rules.get("min"));
            if (rules.containsKey("max") && val.compareTo(decimal(rules.get("max"))) > 0) fail(errorCode, point, "超过最大值 " + rules.get("max"));
            if (rules.containsKey("precision") && val.precision() > decimal(rules.get("precision")).intValueExact()) fail(errorCode, point, "数值超过约定精度");
            if (rules.containsKey("scale") && Math.max(0,val.stripTrailingZeros().scale()) > decimal(rules.get("scale")).intValueExact()) fail(errorCode, point, "数值小数位超过约定精度");
            if (rules.get("reserved_values") instanceof Collection<?> reserved && reserved.stream().anyMatch(item -> equivalent(item, val))) fail(errorCode, point, "设备保留值禁止作为业务值使用");
        }
        if (logicalValue instanceof String str) {
            int length = str.codePointCount(0, str.length());
            if (rules.containsKey("max_length") && length > decimal(rules.get("max_length")).intValueExact()) fail(errorCode, point, "字符串过长");
            if (rules.containsKey("min_length") && length < decimal(rules.get("min_length")).intValueExact()) fail(errorCode, point, "字符串过短");
            Object encoding = rules.get("encoding");
            if (encoding != null && !java.nio.charset.Charset.forName(encoding.toString()).newEncoder().canEncode(str)) fail(errorCode, point, "字符串包含编码无法表示的字符");
        }
        if (logicalValue instanceof List<?> list) {
            if (rules.containsKey("length") && list.size() != decimal(rules.get("length")).intValueExact()) fail(errorCode, point, "数组长度错误");
            if (rules.containsKey("max_items") && list.size() > decimal(rules.get("max_items")).intValueExact()) fail(errorCode, point, "数组超过最大长度");
            if (rules.get("dimensions") instanceof List<?> dimensions) checkDimensions(point, list, dimensions, 0, errorCode);
        }
        if (rules.get("enum") instanceof Collection<?> choices && choices.stream().noneMatch(item -> equivalent(item, raw))) fail(errorCode, point, "不在完整枚举允许值内");
        return "decimal_string".equals(rules.get("wire_encoding")) ? wireValue : logicalValue;
    }

    private static void semantic(String template, Map<String, Object> values) {
        if ("auto_door".equals(template)) {
            long open = numeric(values, "cmd_Open"), close = numeric(values, "cmd_Close");
            if ((open != 0 && open != 1) || (close != 0 && close != 1) || open + close != 1) throw new WcsException(4241, "cmd_Open 与 cmd_Close 必须是0/1整数，且恰好一个为1");
        }
        if ("stacker".equals(template)) {
            long type = numeric(values, "cmd_TaskType");
            if (type < 1 || type > 6) throw new WcsException(4241, "cmd_TaskType 必须为1至6");
            if (type == 2 || type == 3 || type == 4) positiveCoordinates(values, "cmd_TakeCoor_");
            if (type == 1 || type == 3) positiveCoordinates(values, "cmd_PutCoor_");
            if ((type == 1 || type == 2) && numeric(values, "cmd_PortNum") < 1) throw new WcsException(4241, "入库或出库任务的cmd_PortNum必须为有效货口");
        }
    }
    private static void positiveCoordinates(Map<String, Object> values, String prefix) {
        for (String axis : List.of("Row", "Column", "Floor")) if (numeric(values, prefix + axis) < 1) throw new WcsException(4241, prefix + axis + ": 当前任务类型要求有效坐标");
    }
    private static long numeric(Map<String, Object> values, String key) { Object raw = values.get(key); if (!(raw instanceof Number)) throw new WcsException(4241, key + ": 要求整数"); return PointTypeRegistry.number(raw).longValueExact(); }
    private static void checkDimensions(ProtocolPointEntity point, List<?> values, List<?> dims, int level, int code) {
        if (dims.isEmpty() || level >= dims.size() || values.size() != decimal(dims.get(level)).intValueExact()) fail(code, point, "数组维度或长度错误");
        if (level < dims.size() - 1) for (Object child : values) { if (!(child instanceof List<?> list)) fail(code, point, "数组维度错误"); else checkDimensions(point, list, dims, level + 1, code); }
    }
    public static Map<String, Object> constraints(ProtocolPointEntity point) { return point.getConstraints() == null ? Map.of() : point.getConstraints(); }
    public static Map<String, Object> businessConstraints(ProtocolPointEntity point) {
        Map<String, Object> result = new LinkedHashMap<>(constraints(point)); result.remove("byte_order"); result.remove("word_order"); return result;
    }
    private static void nativeMapping(String protocol, ProtocolPointEntity point, String canonical, Map<String,Object> rules) {
        String address=point.getAddress();
        if ("opcua".equals(protocol) && !address.matches("ns=[0-9]+;[isgb]=.+")) fail(4224,point,"OPC UA映射必须是明确的NodeId，不能使用DB偏移代替");
        if ("s7".equals(protocol) || "modbus_tcp".equals(protocol)) {
            var suffix=java.util.regex.Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)(?:\\[([0-9]+)])?$").matcher(address);
            if (suffix.find()) {
                String actual=suffix.group(2)==null?suffix.group(1):"ARRAY["+suffix.group(2)+"] OF "+suffix.group(1);
                if (!canonical.equals(PointTypeRegistry.canonical(protocol,actual))) fail(4224,point,"原生地址声明类型与点位类型不同");
            } else if ("s7".equals(protocol)) fail(4224,point,"S7原生地址必须显式声明类型");
        }
        if ("modbus_tcp".equals(protocol) && (rules.containsKey("byte_order")||rules.containsKey("word_order"))) {
            String nativeAddress=address.replaceFirst(":[A-Za-z][A-Za-z0-9_]*(?:\\[[0-9]+])?$", "");
            if (!(nativeAddress.startsWith("holding-register:")||nativeAddress.matches("4x?[0-9]+"))) fail(4224,point,"自选字节/字序仅支持明确的保持寄存器地址");
            String element=canonical.startsWith("ARRAY<")?canonical.substring(6,canonical.length()-1):canonical;
            int words=switch(element){case "INT16","UINT16"->1;case "INT32","UINT32","FLOAT32"->2;case "INT64","UINT64","FLOAT64"->4;default->0;};
            if(words==0)fail(4224,point,"该类型未实现自选字节/字序");
            long count=1;
            if(canonical.startsWith("ARRAY<")) {
                var array=java.util.regex.Pattern.compile("ARRAY\\s*\\[([0-9]+)]\\s*OF.*",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(point.getData_type().trim());
                if(!array.matches())fail(4224,point,"Modbus自选字节/字序数组必须为固定一维ARRAY[n] OF类型");
                count=Long.parseLong(array.group(1));
            }
            if(count*words>123)fail(4224,point,"Modbus块写超过123寄存器，必须拆成明确定义的点位");
        }
    }
    public static String identity(ProtocolPointEntity point) { return point.getTemplate_code() + "/" + point.getTemplate_version() + "/" + point.getOperation() + "/" + point.getDirection() + "/" + point.getField_name(); }
    public static boolean equivalent(Object a, Object b) {
        if (a instanceof Number || a instanceof String && b instanceof Number) { try { return decimal(a).compareTo(decimal(b)) == 0; } catch (RuntimeException ex) { return false; } }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) return x.keySet().equals(y.keySet()) && x.keySet().stream().allMatch(key -> equivalent(x.get(key), y.get(key)));
        if (a instanceof List<?> x && b instanceof List<?> y) { if (x.size() != y.size()) return false; for (int i = 0; i < x.size(); i++) if (!equivalent(x.get(i), y.get(i))) return false; return true; }
        return Objects.equals(a, b);
    }
    private static boolean integerCompatible(String logical, String nativeType, Map<String, Object> rules) {
        if (!(logical.startsWith("INT") || logical.startsWith("UINT")) || !(nativeType.startsWith("INT") || nativeType.startsWith("UINT"))) return false;
        if (!rules.containsKey("min") || !rules.containsKey("max")) return false;
        try { PointTypeRegistry.encode("sim", logical, decimal(rules.get("min"))); PointTypeRegistry.encode("sim", logical, decimal(rules.get("max"))); PointTypeRegistry.encode("sim", nativeType, decimal(rules.get("min"))); PointTypeRegistry.encode("sim", nativeType, decimal(rules.get("max"))); return true; }
        catch (RuntimeException ex) { return false; }
    }
    private static BigDecimal decimal(Object value) { return PointTypeRegistry.number(value); }
    private static void fail(int code, ProtocolPointEntity point, String message) { throw new WcsException(code, point.getField_name() + " (" + point.getDirection() + "): " + message); }
}
