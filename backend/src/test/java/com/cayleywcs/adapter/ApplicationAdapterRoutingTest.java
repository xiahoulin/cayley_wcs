package com.cayleywcs.adapter;

import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.adapter.seer.*;
import com.cayleywcs.adapter.tcp.*;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ApplicationAdapterRoutingTest {
    @Test void tcpTransportAndSeerApplicationPacketsHaveIndependentRegistrations() {
        var factory=new ProtocolAdapterFactory(List.of(new TcpAdapterProvider()),List.of(new SeerTcpAdapterProvider(new DeviceEndpoints(new MockEnvironment()))));
        var protocol=new ProtocolEntity();protocol.setProtocol_type("tcp");
        var app=new ApplicationEntity();app.setDirection("downstream");app.setDevice_type("agv");
        var context=new AdapterContext(app,protocol,List.of());
        assertThat(factory.create(context)).isInstanceOf(TcpAdapter.class);
        app.setDevice_type(null);assertThat(factory.create(context)).isInstanceOf(TcpAdapter.class);
        app.setDevice_type("agv");
        app.setAdapter_type("seer_agv");
        assertThat(factory.create(context)).isInstanceOf(SeerTcpAdapter.class);
        assertThat(factory.create(context).protocolType()).isEqualTo("tcp");
        assertThat(factory.supports("tcp")).isTrue();
        assertThat(factory.supports("seer_tcp")).isFalse();assertThat(factory.supports("sensor_gateway")).isFalse();
        assertThat(PointTypeRegistry.PROTOCOLS).doesNotContain("seer_tcp","sensor_gateway");
        protocol.setProtocol_type("modbus_tcp");
        assertThatThrownBy(()->factory.create(context)).hasMessageContaining("不匹配");
        protocol.setProtocol_type("tcp");app.setDevice_type("stacker");
        assertThatThrownBy(()->factory.create(context)).hasMessageContaining("不匹配");
        app.setAdapter_type("unknown");assertThatThrownBy(()->factory.create(context)).hasMessageContaining("未注册");
    }
}
