package com.cayleywcs.adapter.mqtt;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolException;
import com.cayleywcs.adapter.message.PointMessage;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

/** 显式选择关联点位对象或单点 JSON；PUBACK 仅表示代理接收，点位对象另等设备回执。 */
public class MqttAdapter extends AbstractProtocolAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Pending(String topic, CompletableFuture<PointMessage> reply) { }
    private record Sample(byte[] body, long collectedAt) { }
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Sample> latest = new ConcurrentHashMap<>();
    private volatile MqttClient client;
    private int qos;
    private long timeoutMs;
    private int maxMessageBytes;
    private String format;

    @Override public String protocolType() { return "mqtt"; }

    @Override protected void doConnect(AdapterContext ctx) throws Exception {
        qos = Math.toIntExact(ctx.connParamLong("qos", 1));
        timeoutMs = ctx.connParamLong("requestTimeoutMs", 5000);
        maxMessageBytes = Math.toIntExact(ctx.connParamLong("maxMessageBytes", 1048576));
        format = ctx.connParam("messageFormat", "point-json-v1");
        if (qos < 0 || qos > 2 || timeoutMs <= 0 || maxMessageBytes <= 0 || maxMessageBytes > 8388608
                || !("point-json-v1".equals(format) || "single-point-json".equals(format))) {
            throw new IllegalArgumentException("MQTT 消息格式、QoS、长度上限或超时无效");
        }
        latest.clear();
        client = new MqttClient(ctx.connParam("broker", "tcp://127.0.0.1:1883"),
                ctx.connParam("clientId", "wcs-" + ctx.application().getApp_code()), new MemoryPersistence());
        client.setTimeToWait(ctx.connParamLong("connectTimeoutMs", 5000));
        client.setCallback(new ResponseCallback());
        var options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(true);
        String username = ctx.connParam("username", "");
        if (!username.isBlank()) {
            options.setUserName(username);
            options.setPassword(ctx.connParam("password", "").getBytes(StandardCharsets.UTF_8));
        }
        client.connect(options);
        subscribe();
        client.setTimeToWait(Math.max(1000, timeoutMs));
    }

    private void subscribe() throws MqttException {
        for (ProtocolPointEntity point : context.points()) {
            // 关联点位报文的请求写入也需要设备回执；单点采样则订阅响应和驱动内部点位。
            if ("point-json-v1".equals(format)) { client.subscribe(responseTopic(point), qos); }
            else if ("response".equals(point.getDirection()) || "internal".equals(point.getPoint_scope())) {
                client.subscribe(point.getAddress(), qos);
            }
        }
    }

    @Override protected void doDisconnect() throws Exception {
        failPending("MQTT 连接已关闭");
        latest.clear();
        MqttClient active = client;
        client = null;
        if (active != null) {
            try { if (active.isConnected()) { active.disconnect(); } }
            finally { active.close(); }
        }
    }

    @Override public boolean isConnected() { return client != null && client.isConnected(); }
    @Override public boolean heartbeat() { return isConnected(); }

    @Override public Object read(ProtocolPointEntity point) {
        requireConnected();
        if ("point-json-v1".equals(format)) {
            return decodeResponse(point, exchange(point,
                    PointMessage.request("read", point.getField_name(), null)));
        }
        try {
            Sample sample = latest.get(point.getAddress());
            if (sample == null || System.currentTimeMillis() - sample.collectedAt >
                    context.connParamLong("maxSampleAgeMs", 10000)) {
                throw new IllegalStateException("MQTT 点位没有新鲜采集值");
            }
            return decodeResponse(point, JSON.readValue(sample.body, Object.class));
        } catch (Exception ex) { throw ProtocolException.io(point.getField_name(), ex); }
    }

    @Override public void write(ProtocolPointEntity point, Object value) {
        requireConnected();
        Object encoded = encodeValue(point, value);
        if ("point-json-v1".equals(format)) {
            verifyWriteEcho(point, encoded, exchange(point, PointMessage.request("write", point.getField_name(), encoded)));
        } else {
            try {
                byte[] bytes = JSON.writeValueAsBytes(encoded);
                checkLength(bytes);
                client.publish(point.getAddress(), bytes, qos, false);
            } catch (Exception ex) { throw ProtocolException.io(point.getField_name(), ex); }
        }
    }

    private Object exchange(ProtocolPointEntity point, PointMessage request) {
        CompletableFuture<PointMessage> reply = new CompletableFuture<>();
        pending.put(request.correlation_id(), new Pending(responseTopic(point), reply));
        try {
            byte[] bytes = request.bytes();
            checkLength(bytes);
            client.publish(requestTopic(point), bytes, qos, false);
            return reply.get(timeoutMs, TimeUnit.MILLISECONDS).responseValue(request, point.getField_name());
        } catch (Exception ex) { throw ProtocolException.io(point.getField_name(), ex); }
        finally { pending.remove(request.correlation_id()); }
    }

    private String requestTopic(ProtocolPointEntity point) {
        String topic = context.connParam("requestTopic", point.getAddress());
        validateTopic(topic);
        return topic;
    }
    private String responseTopic(ProtocolPointEntity point) {
        String topic = context.connParam("responseTopic", point.getAddress() + "/response");
        validateTopic(topic);
        return topic;
    }
    private void validateTopic(String topic) {
        if (topic == null || topic.isBlank() || topic.contains("+") || topic.contains("#")) {
            throw new IllegalArgumentException("MQTT 关联主题必须是确定的主题名称");
        }
    }
    private void checkLength(byte[] bytes) {
        if (bytes.length > maxMessageBytes) { throw new IllegalArgumentException("MQTT 消息超过长度上限"); }
    }
    private void failPending(String message) {
        pending.values().forEach(p -> p.reply.completeExceptionally(new IllegalStateException(message)));
        pending.clear();
    }

    private final class ResponseCallback implements MqttCallback {
        @Override public void disconnected(MqttDisconnectResponse response) {
            latest.clear(); failPending("MQTT 连接中断"); markDisconnected();
        }
        @Override public void mqttErrorOccurred(MqttException exception) { failPending("MQTT 协议错误"); }
        @Override public void messageArrived(String topic, MqttMessage message) {
            // A retained sample, duplicate or late reply cannot complete a new request.
            if (message.isRetained() || message.getPayload().length > maxMessageBytes) { return; }
            if ("single-point-json".equals(format)) {
                latest.put(topic, new Sample(message.getPayload().clone(), System.currentTimeMillis()));
                return;
            }
            try {
                PointMessage body = PointMessage.parse(message.getPayload());
                Pending waiter = pending.get(body.correlation_id());
                if (waiter != null && waiter.topic.equals(topic)) { waiter.reply.complete(body); }
            } catch (RuntimeException ex) { log.warn("MQTT 点位响应格式无效，等待请求超时"); }
        }
        @Override public void deliveryComplete(IMqttToken token) { }
        @Override public void connectComplete(boolean reconnect, String uri) {
            if (reconnect) {
                latest.clear(); failPending("MQTT 重连，原操作结果不明，不自动重放");
                try { subscribe(); } catch (MqttException ex) { markDisconnected(); }
            }
        }
        @Override public void authPacketArrived(int reasonCode, MqttProperties properties) { }
    }
}
