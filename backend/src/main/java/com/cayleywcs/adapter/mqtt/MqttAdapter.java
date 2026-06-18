package com.cayleywcs.adapter.mqtt;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

/**
 * MQTT 适配器（Eclipse Paho v5）。订阅可读点位的 topic 并缓存最新报文；写为向 topic 发布。
 * point.address 即 topic；conn_params：broker / clientId / username / password / qos。
 */
public class MqttAdapter extends AbstractProtocolAdapter {
    private final Map<String, String> latest = new ConcurrentHashMap<>();
    private volatile MqttClient client;
    private int qos = 1;

    @Override
    public String protocolType() {
        return "mqtt";
    }

    @Override
    protected void doConnect(AdapterContext ctx) throws Exception {
        String broker = ctx.connParam("broker", "tcp://127.0.0.1:1883");
        String clientId = ctx.connParam("clientId", "wcs-" + ctx.application().getApp_code());
        this.qos = (int) ctx.connParamLong("qos", 1);

        client = new MqttClient(broker, clientId, new MemoryPersistence());
        client.setCallback(new CacheCallback());

        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        options.setCleanStart(true);
        String username = ctx.connParam("username", "");
        if (!username.isBlank()) {
            options.setUserName(username);
            options.setPassword(ctx.connParam("password", "").getBytes(StandardCharsets.UTF_8));
        }
        client.connect(options);

        for (ProtocolPointEntity point : ctx.points()) {
            String rw = point.getRw() == null ? "R" : point.getRw().toUpperCase();
            if (rw.contains("R") && point.getAddress() != null && !point.getAddress().isBlank()) {
                client.subscribe(point.getAddress(), qos);
            }
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        MqttClient c = client;
        client = null;
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect();
                }
            } finally {
                c.close();
            }
        }
    }

    @Override
    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    @Override
    public boolean heartbeat() {
        return isConnected();
    }

    @Override
    public Object read(ProtocolPointEntity point) {
        return latest.get(point.getAddress());
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        try {
            byte[] payload = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            client.publish(point.getAddress(), payload, qos, false);
        } catch (MqttException ex) {
            throw new RuntimeException("MQTT publish failed: " + point.getAddress(), ex);
        }
    }

    private final class CacheCallback implements MqttCallback {
        @Override
        public void disconnected(MqttDisconnectResponse disconnectResponse) {
            markDisconnected();
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
            log.debug("mqtt error: {}", exception.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            latest.put(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
        }

        @Override
        public void deliveryComplete(IMqttToken token) {
        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
        }

        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {
        }
    }
}
