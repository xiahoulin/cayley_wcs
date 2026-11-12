package com.cayleywcs.telemetry.storage.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.telemetry.entity.*;
import com.cayleywcs.telemetry.mapper.*;
import com.cayleywcs.telemetry.storage.TelemetryStorageService;
import java.util.Map;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryStorageServiceImpl implements TelemetryStorageService {
    @org.springframework.beans.factory.annotation.Autowired private com.cayleywcs.common.support.ResourceLifecycleGuard lifecycle;
    private final TelemetryCycleMapper cycles;
    private final TelemetryLatestMapper latest;
    private final SensorGatewayMapper gateways;
    public TelemetryStorageServiceImpl(TelemetryCycleMapper cycles, TelemetryLatestMapper latest, SensorGatewayMapper gateways) {
        this.cycles=cycles; this.latest=latest; this.gateways=gateways;
    }
    @Override @Transactional public void save(TelemetryCycleEntity cycle) {
        if(lifecycle!=null) lifecycle.references();
        if (cycles.selectCount(new QueryWrapper<TelemetryCycleEntity>().eq("cycle_id",cycle.getCycle_id()))>0) return;
        var gateway=gateways.selectById(cycle.getGateway_id());
        if (gateway==null || !Objects.equals(gateway.getTenant_id(),cycle.getTenant_id())) return;
        cycle.setId(null); cycles.insert(cycle);
        if (!Objects.equals(gateway.getConfig_version(),cycle.getConfig_version())) return;
        for (var entry:cycle.getMeasurements().entrySet()) {
            @SuppressWarnings("unchecked") Map<String,Object> sample=(Map<String,Object>)entry.getValue();
            long time=((Number)sample.get("receivedAt")).longValue();
            var row=latest.selectOne(new QueryWrapper<TelemetryLatestEntity>().eq("gateway_id",cycle.getGateway_id()).eq("metric",entry.getKey()));
            if (row!=null && row.getObserved_at()>time) continue;
            boolean fresh=row==null;
            if (fresh) { row=new TelemetryLatestEntity(); Audits.fillCreate(row,null); row.setTenant_id(cycle.getTenant_id()); row.setGateway_id(cycle.getGateway_id()); row.setMetric(entry.getKey()); }
            // A changed profile must never reuse an older calibration's last-good value.
            if (!Objects.equals(row.getConfig_version(),cycle.getConfig_version())) row.setLast_good(Map.of());
            row.setConfig_version(cycle.getConfig_version()); row.setObserved_at(time); row.setSample(sample);
            if ("GOOD".equals(sample.get("quality"))) row.setLast_good(sample);
            Audits.touch(row);
            if (fresh) latest.insert(row); else latest.updateById(row);
        }
    }
    @Scheduled(fixedDelayString="${cayleywcs.telemetry.retention-scan-ms:3600000}",initialDelayString="${cayleywcs.telemetry.retention-scan-ms:3600000}")
    public void retainThirtyDays() {
        cycles.delete(new QueryWrapper<TelemetryCycleEntity>().lt("finished_at",System.currentTimeMillis()-30L*24*60*60*1000));
    }
}
