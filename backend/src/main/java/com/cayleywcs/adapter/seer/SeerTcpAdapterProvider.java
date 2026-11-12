package com.cayleywcs.adapter.seer;

import com.cayleywcs.adapter.*;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import org.springframework.stereotype.Component;

@Component
public class SeerTcpAdapterProvider implements ApplicationAdapterProvider {
    private final DeviceEndpoints endpoints;
    public SeerTcpAdapterProvider(DeviceEndpoints endpoints) { this.endpoints = endpoints; }
    @Override public String adapterType() { return "seer_agv"; }
    @Override public ProtocolAdapter create(AdapterContext ctx) { return new SeerTcpAdapter(endpoints); }
}
