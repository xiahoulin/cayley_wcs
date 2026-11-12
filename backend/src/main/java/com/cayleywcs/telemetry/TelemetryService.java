package com.cayleywcs.telemetry;

import com.cayleywcs.common.api.PageData;
import com.cayleywcs.telemetry.entity.*;
import java.util.List;
import java.util.Map;

public interface TelemetryService {
    record Configure(Long id, Long agvAppId, String endpointRef, Integer infraredOffset,
                     Double infraredMin, Double infraredMax, Long expectedVersion) { }
    record Enable(Long id, boolean enabled, Long expectedVersion) { }
    record Power(Long id, Long powerOnAt, String evidence, Long expectedVersion) { }
    record History(Long gatewayId, Long beforeId, Long fromAt, Long toAt, int limit) { }
    SensorGatewayEntity configure(Configure request);
    SensorGatewayEntity enable(Enable request);
    SensorGatewayEntity power(Power request);
    List<SensorGatewayEntity> list(Long agvAppId);
    Map<String,Object> latest(Long gatewayId);
    PageData<TelemetryCycleEntity> history(History request);
}
