package com.cayleywcs.adapter.message;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.cayleywcs.adapter.ProtocolException;

/** HTTP、MQTT、长度前缀 TCP 的点位消息；关联号与时间共同拒绝陈旧响应。 */
public record PointMessage(int version, String operation, String correlation_id, String direction,
                           Map<String, Object> payload, long timestamp, String error) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public static PointMessage request(String operation, String field, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(field, value);
        return new PointMessage(1, operation, UUID.randomUUID().toString(), "request", payload,
                System.currentTimeMillis(), null);
    }

    public byte[] bytes() {
        try { return JSON.writeValueAsBytes(this); }
        catch (IOException ex) { throw new ProtocolException(4224,"点位对象不能编码为 JSON", ex); }
    }

    public static PointMessage parse(byte[] bytes) {
        try {
            PointMessage message = JSON.readValue(bytes, PointMessage.class);
            if (message.version != 1 || message.operation == null || message.correlation_id == null
                    || message.correlation_id.isBlank() || message.payload == null || message.timestamp <= 0
                    || !("request".equals(message.direction) || "response".equals(message.direction))) {
                throw new ProtocolException(4225,"点位消息版本、方向、关联号或 payload 无效");
            }
            return message;
        } catch (IOException ex) { throw new ProtocolException(4225,"点位响应不是合法 JSON 对象", ex); }
    }

    public Object responseValue(PointMessage request, String field) {
        if (!"response".equals(direction) || !operation.equals(request.operation)
                || !correlation_id.equals(request.correlation_id)
                || timestamp < request.timestamp || timestamp > System.currentTimeMillis() + 5000) {
            throw new ProtocolException(4225,"点位响应方向、操作、关联号或采集时间不匹配");
        }
        if (error != null && !error.isBlank()) { throw new ProtocolException(4222,"设备拒绝点位操作：" + error); }
        if (payload.size() != 1 || !payload.containsKey(field) || payload.get(field) == null) {
            throw new ProtocolException(4225,"点位响应字段不完整或包含未请求字段：" + field);
        }
        return payload.get(field);
    }
}
