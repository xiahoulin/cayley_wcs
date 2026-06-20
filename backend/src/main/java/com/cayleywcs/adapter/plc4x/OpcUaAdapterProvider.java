package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import org.springframework.stereotype.Component;

@Component
public class OpcUaAdapterProvider implements ProtocolAdapterProvider {

    @Override
    public String protocolType() {
        return "opcua";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        long timeout = ctx.connParamLong("requestTimeoutMs", 5000);
        // OPC UA 适配器带「主动 WCS 心跳」（协议定义了可写心跳点时翻转写入，否则退化为被动心跳）。
        return new StackerOpcUaAdapter(Plc4xConnectionStrings.build("opcua", ctx), timeout);
    }
}
