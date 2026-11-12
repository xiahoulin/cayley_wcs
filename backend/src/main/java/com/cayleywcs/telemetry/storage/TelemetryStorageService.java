package com.cayleywcs.telemetry.storage;

import com.cayleywcs.telemetry.entity.TelemetryCycleEntity;

public interface TelemetryStorageService {
    void save(TelemetryCycleEntity cycle);
}
