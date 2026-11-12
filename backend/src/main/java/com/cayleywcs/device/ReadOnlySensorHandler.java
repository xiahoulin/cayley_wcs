package com.cayleywcs.device;

import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.telemetry.profile.SensorPointCatalog;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReadOnlySensorHandler implements DeviceHandler {
    @Override public String deviceType() { return "vibration_sensor"; }
    @Override public List<String> deviceTypes() { return List.copyOf(SensorPointCatalog.UNITS.keySet()); }
    @Override public boolean advance(TaskEntity task) { throw new WcsException(4223,"传感器应用仅采集状态，不接受设备任务"); }
}
