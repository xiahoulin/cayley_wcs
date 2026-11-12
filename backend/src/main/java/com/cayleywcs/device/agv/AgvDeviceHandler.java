package com.cayleywcs.device.agv;

import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.device.DeviceHandler;
import com.cayleywcs.task.entity.TaskEntity;
import org.springframework.stereotype.Component;

/** Permits read-only connections; task admission and the adapter both prohibit motion. */
@Component
public class AgvDeviceHandler implements DeviceHandler {
    @Override public String deviceType() { return "agv"; }
    @Override public boolean advance(TaskEntity task) { throw new WcsException(4223,"AGV 导航闭环尚未启用，禁止派工"); }
}
