package com.cayleywcs.connection;

import java.util.List;
import java.util.Map;

public interface DeviceStateService {
    record Setting(Long appId,Long revisionId,Long configurationEpoch,Map<String,Object> payload) { }
    List<Map<String,Object>> applications();
    Map<String,Object> state(Long appId);
    Map<String,Object> set(Setting request);
}
