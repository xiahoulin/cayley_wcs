package com.cayleywcs.adapter.sim;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import com.cayleywcs.simulator.StackerSimulatorRegistry;
import org.springframework.stereotype.Component;

@Component
public class SimAdapterProvider implements ProtocolAdapterProvider {
    private final StackerSimulatorRegistry registry;

    public SimAdapterProvider(StackerSimulatorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String protocolType() {
        return "sim";
    }

    @Override
    public ProtocolAdapter create(AdapterContext ctx) {
        return new SimAdapter(registry, ctx.application().getId());
    }
}
