package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.adapter.PointTypeRegistry;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Modbus 每元素的寄存器字节/字序；数组元素顺序不因字序设置而反转。 */
final class ModbusRegisterCodec {
    private final String element;
    private final boolean array;
    private final int count;
    private final int wordsPerElement;
    private final boolean reverseBytes;
    private final boolean reverseWords;

    ModbusRegisterCodec(ProtocolPointEntity point) {
        String type = PointTypeRegistry.canonical("modbus_tcp", point.getData_type());
        array = type.startsWith("ARRAY<");
        element = array ? type.substring(6, type.length()-1) : type;
        wordsPerElement = switch(element) {
            case "INT16", "UINT16" -> 1;
            case "INT32", "UINT32", "FLOAT32" -> 2;
            case "INT64", "UINT64", "FLOAT64" -> 4;
            default -> throw new IllegalArgumentException("该 Modbus 类型未定义自选字节/字序："+element);
        };
        if (array) {
            var matcher = java.util.regex.Pattern.compile("ARRAY\\s*\\[([0-9]+)]\\s*OF.*", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(point.getData_type().trim());
            if (!matcher.matches()) { throw new IllegalArgumentException("Modbus 字节/字序数组必须声明固定一维长度"); }
            count = Integer.parseInt(matcher.group(1));
        } else { count = 1; }
        if ((long)count*wordsPerElement > 123) { throw new IllegalArgumentException("Modbus 一次块写超过123寄存器，必须分成明确的点位"); }
        Map<String,Object> rules = point.getConstraints() == null ? Map.of() : point.getConstraints();
        reverseBytes = order(rules.get("byte_order"));
        reverseWords = order(rules.get("word_order"));
    }

    static boolean configured(ProtocolPointEntity point) {
        return point.getConstraints()!=null && (point.getConstraints().containsKey("byte_order")
                || point.getConstraints().containsKey("word_order"));
    }

    String address(ProtocolPointEntity point) {
        String address = point.getAddress().replaceFirst(":[A-Za-z][A-Za-z0-9_]*(?:\\[[0-9]+])?$", "");
        if (!(address.startsWith("holding-register:") || address.matches("4x?[0-9]+"))) {
            throw new IllegalArgumentException("Modbus 自选字节/字序只支持明确的保持寄存器地址");
        }
        return address+":UINT["+(count*wordsPerElement)+"]";
    }

    Object[] encode(Object encoded) {
        List<?> values = array ? (List<?>)encoded : List.of(encoded);
        if (values.size()!=count) { throw new IllegalArgumentException("Modbus 数组长度不匹配"); }
        List<Integer> registers = new ArrayList<>();
        for (Object value : values) {
            var buffer = ByteBuffer.allocate(wordsPerElement*2);
            Number number = (Number)value;
            switch(element) {
                case "INT16", "UINT16" -> buffer.putShort(number.shortValue());
                case "INT32", "UINT32" -> buffer.putInt(number.intValue());
                case "INT64", "UINT64" -> buffer.putLong(number.longValue());
                case "FLOAT32" -> buffer.putFloat(number.floatValue());
                case "FLOAT64" -> buffer.putDouble(number.doubleValue());
                default -> throw new IllegalArgumentException("Modbus 类型无法编码");
            }
            byte[] bytes = buffer.array();
            for (int word=0; word<wordsPerElement; word++) {
                int index = (reverseWords ? wordsPerElement-1-word : word)*2;
                int high = Byte.toUnsignedInt(bytes[index+(reverseBytes ? 1 : 0)]);
                int low = Byte.toUnsignedInt(bytes[index+(reverseBytes ? 0 : 1)]);
                registers.add((high<<8)|low);
            }
        }
        return registers.toArray();
    }

    Object decode(Object raw) {
        List<Object> registers = raw instanceof java.util.Collection<?> || raw.getClass().isArray()
                ? PointTypeRegistry.sequence(raw) : List.of(raw);
        if (registers.size()!=count*wordsPerElement) { throw new IllegalArgumentException("Modbus 返回寄存器数量不完整"); }
        List<Object> values = new ArrayList<>();
        for (int value=0; value<count; value++) {
            byte[] bytes = new byte[wordsPerElement*2];
            for (int word=0; word<wordsPerElement; word++) {
                int register = ((Number)registers.get(value*wordsPerElement+word)).intValue();
                int index = (reverseWords ? wordsPerElement-1-word : word)*2;
                bytes[index+(reverseBytes ? 1 : 0)] = (byte)(register>>>8);
                bytes[index+(reverseBytes ? 0 : 1)] = (byte)register;
            }
            var buffer = ByteBuffer.wrap(bytes);
            values.add(switch(element) {
                case "INT16" -> buffer.getShort(); case "UINT16" -> Short.toUnsignedInt(buffer.getShort());
                case "INT32" -> buffer.getInt(); case "UINT32" -> Integer.toUnsignedLong(buffer.getInt());
                case "INT64" -> buffer.getLong(); case "UINT64" -> new BigInteger(1, bytes);
                case "FLOAT32" -> buffer.getFloat(); case "FLOAT64" -> buffer.getDouble();
                default -> throw new IllegalArgumentException("Modbus 类型无法解码");
            });
        }
        return array ? values : values.getFirst();
    }

    private static boolean order(Object value) {
        if (value == null || "BIG_ENDIAN".equals(value)) { return false; }
        if ("LITTLE_ENDIAN".equals(value)) { return true; }
        throw new IllegalArgumentException("字节/字序必须为 BIG_ENDIAN 或 LITTLE_ENDIAN");
    }
}
