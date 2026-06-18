package com.cayleywcs.adapter.http;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import org.springframework.stereotype.Component;

@Component
public class HttpAdapterProvider implements ProtocolAdapterProvider {

    @Override
    public String protocolType() {
        return "http";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        return new HttpAdapter();
    }
}
