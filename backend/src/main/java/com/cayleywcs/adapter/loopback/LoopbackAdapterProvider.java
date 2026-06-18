package com.cayleywcs.adapter.loopback;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import org.springframework.stereotype.Component;

@Component
public class LoopbackAdapterProvider implements ProtocolAdapterProvider {

    @Override
    public String protocolType() {
        return "loopback";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        return new LoopbackAdapter();
    }
}
