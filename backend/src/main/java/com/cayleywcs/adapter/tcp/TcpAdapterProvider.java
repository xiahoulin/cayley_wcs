package com.cayleywcs.adapter.tcp;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import org.springframework.stereotype.Component;

@Component
public class TcpAdapterProvider implements ProtocolAdapterProvider {

    @Override
    public String protocolType() {
        return "tcp";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        return new TcpAdapter();
    }
}
