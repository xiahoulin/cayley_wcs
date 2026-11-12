package com.cayleywcs.adapter.sensor;

import com.cayleywcs.adapter.*;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.telemetry.SensorApplicationService;
import com.cayleywcs.telemetry.mapper.SensorGatewayMapper;
import com.cayleywcs.telemetry.polling.SensorPollingService;
import com.cayleywcs.telemetry.profile.SensorPointCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SensorGatewayAdapterProvider implements ApplicationAdapterProvider {
    private final ObjectProvider<SensorPollingService> polling;
    private final SensorApplicationService applications;
    private final SensorGatewayMapper gateways;
    private final DeviceEndpoints endpoints;
    public SensorGatewayAdapterProvider(ObjectProvider<SensorPollingService> polling,SensorApplicationService applications,SensorGatewayMapper gateways,DeviceEndpoints endpoints) {
        this.polling=polling;this.applications=applications;this.gateways=gateways;this.endpoints=endpoints;
    }
    @Override public String adapterType(){return "dr164_sensor";}
    @Override public ProtocolAdapter create(AdapterContext context){return new SensorGatewayAdapter(polling.getObject(),applications,gateways,endpoints);}
}
