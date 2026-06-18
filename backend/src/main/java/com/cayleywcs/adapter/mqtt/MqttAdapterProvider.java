package com.cayleywcs.adapter.mqtt;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import org.springframework.stereotype.Component;

@Component
public class MqttAdapterProvider implements ProtocolAdapterProvider {

    @Override
    public String protocolType() {
        return "mqtt";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        return new MqttAdapter();
    }
}
