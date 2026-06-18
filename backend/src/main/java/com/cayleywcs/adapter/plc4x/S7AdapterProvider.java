package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import org.springframework.stereotype.Component;

@Component
public class S7AdapterProvider implements ProtocolAdapterProvider {

    @Override
    public String protocolType() {
        return "s7";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        long timeout = ctx.connParamLong("requestTimeoutMs", 5000);
        return new Plc4xAdapter("s7", Plc4xConnectionStrings.build("s7", ctx), timeout);
    }
}
