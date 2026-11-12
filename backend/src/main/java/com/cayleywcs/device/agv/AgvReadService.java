package com.cayleywcs.device.agv;

import java.util.List;
import java.util.Map;

public interface AgvReadService {
    record Create(String appCode, String appName, String endpointRef) { }
    Map<String,Object> create(Create request);
    Map<String,Object> upgrade(Long appId);
    List<Map<String,Object>> list();
    Map<String,Object> state(Long appId);
    Map<String,Object> context(Long appId);
}
