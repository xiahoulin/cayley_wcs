package com.cayleywcs.device.agv;

import java.util.Map;

public interface AgvNavigationService {
    record Command(Long appId,Long revisionId,Long configurationEpoch,String requestId,String stationName) { }
    record Acknowledge(Long appId,String requestId) { }
    Map<String,Object> enable(Long appId);
    Map<String,Object> state(Long appId);
    Map<String,Object> send(Command request);
    Map<String,Object> acknowledge(Acknowledge request);
}
