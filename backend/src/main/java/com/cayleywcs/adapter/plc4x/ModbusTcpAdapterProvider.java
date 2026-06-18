package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import org.springframework.stereotype.Component;

@Component
public class ModbusTcpAdapterProvider implements ProtocolAdapterProvider {

    @Override
    public String protocolType() {
        return "modbus_tcp";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        long timeout = ctx.connParamLong("requestTimeoutMs", 5000);
        return new Plc4xAdapter("modbus_tcp", Plc4xConnectionStrings.build("modbus_tcp", ctx), timeout);
    }
}
