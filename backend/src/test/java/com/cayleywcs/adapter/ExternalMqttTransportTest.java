package com.cayleywcs.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.context;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.point;
import com.cayleywcs.adapter.mqtt.MqttAdapter;
import com.cayleywcs.common.exception.WcsException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="WCS_EXTERNAL_SIMULATORS", matches="true")
class ExternalMqttTransportTest {
    public static void main(String[] args) throws Exception {
        new ExternalMqttTransportTest().readsWritesCorrelatedPointsAndRejectsRetainedAndTimeout();
        new ExternalMqttTransportTest().singlePointSubscriptionsFollowResponseAndInternalDriverScope();
        System.out.println("MQTT_REAL_NETWORK_PASS broker/device/payload/QoS1/correlation/retained/timeout");
    }

    @Test void readsWritesCorrelatedPointsAndRejectsRetainedAndTimeout() throws Exception {
        var adapter = new MqttAdapter(); var speed = point("speed", "wcs-test/request", "INT16", "request");
        adapter.connect(context("mqtt", List.of(speed), Map.of("broker", "tcp://127.0.0.1:28883",
                "clientId", "wcs-isolated-client", "requestTopic", "wcs-test/request", "responseTopic", "wcs-test/response",
                "requestTimeoutMs", 1000)));
        try {
            adapter.write(speed, 321); assertThat(adapter.read(speed)).isEqualTo(321L);
            adapter.write(speed, -123); assertThat(adapter.read(speed)).isEqualTo(-123L);
        } finally { adapter.disconnect(); }
        adapter.connect(context("mqtt", List.of(speed), Map.of("broker", "tcp://127.0.0.1:28883",
                "clientId", "wcs-isolated-client", "requestTopic", "wcs-test/no-device", "responseTopic", "wcs-test/response",
                "requestTimeoutMs", 150)));
        try { assertThatThrownBy(() -> adapter.read(speed)).isInstanceOfSatisfying(com.cayleywcs.common.exception.WcsException.class,error->assertThat(error.getCode()).isEqualTo(4222)); }
        finally { adapter.disconnect(); }
    }

    @Test void singlePointSubscriptionsFollowResponseAndInternalDriverScope() throws Exception {
        String prefix="wcs-direction-"+java.util.UUID.randomUUID();
        var request=point("cmd_TaskType",prefix+"/request","INT16","request");
        var response=point("status_Task",prefix+"/response","INT16","response");
        var number=point("cmd_TaskNum",prefix+"/number","UINT16","request");
        number.setPoint_scope("internal");
        var adapter=new MqttAdapter();
        var device=new org.eclipse.paho.mqttv5.client.MqttClient("tcp://127.0.0.1:28883",prefix+"-device",
                new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        device.connect();
        adapter.connect(context("mqtt",List.of(request,response,number),Map.of("broker","tcp://127.0.0.1:28883",
                "clientId",prefix+"-wcs","messageFormat","single-point-json")));
        try {
            device.publish(request.getAddress(),"7".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
            device.publish(response.getAddress(),"2".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
            device.publish(number.getAddress(),"321".getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);
            long deadline=System.currentTimeMillis()+2000;
            while (true) {
                try {
                    assertThat(adapter.read(response)).isEqualTo(2L);
                    assertThat(adapter.read(number)).isEqualTo(321L);
                    break;
                } catch (WcsException ex) {
                    if (System.currentTimeMillis()>=deadline) throw ex;
                    Thread.sleep(20);
                }
            }
            assertThatThrownBy(()->adapter.read(request))
                    .isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4222));
            assertThat(adapter.readAll(List.of(request,response,number))).containsOnlyKeys("status_Task");
        } finally {
            adapter.disconnect(); device.disconnect(); device.close();
        }
    }
}
