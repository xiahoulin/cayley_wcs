package com.cayleywcs.telemetry;

import com.cayleywcs.telemetry.entity.SensorGatewayEntity;
import java.util.List;
import java.util.Set;

public interface SensorApplicationService {
    record Link(Long appId,Long gatewayId,String sensorKind,Long tenantId) { }
    List<Link> provision(Long gatewayId);
    Link find(Long appId,Long tenantId);
    boolean hasApplications(Long gatewayId);
    Set<Integer> activeUnits(SensorGatewayEntity gateway);
}
