package com.cayleywcs.telemetry.polling;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.application.mapper.ApplicationMapper;
import com.cayleywcs.common.support.Audits;
import com.cayleywcs.device.agv.AgvReadService;
import com.cayleywcs.telemetry.entity.*;
import com.cayleywcs.telemetry.SensorApplicationService;
import com.cayleywcs.telemetry.mapper.SensorGatewayMapper;
import com.cayleywcs.telemetry.gateway.ModbusReadSession;
import com.cayleywcs.telemetry.profile.SensorProfile;
import com.cayleywcs.telemetry.storage.TelemetryBuffer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Independent observation loop: no task-engine entry, device locks, or database writes. */
@Component
public class SensorPollingService {
    private static final int MAX_GATEWAYS=32;
    private static final long PERIOD_MS=2000, DRAIN_MS=2000;
    private static final class RuntimeState {
        final SensorGatewayEntity config;
        final ModbusReadSession bus;
        final String endpointKey;
        final AtomicBoolean busy=new AtomicBoolean();
        final Map<String,Object> lastGood=new LinkedHashMap<>();
        final Map<String,Integer> blockFailures=new HashMap<>();
        final Map<String,Long> probeAt=new HashMap<>();
        final Map<Integer,Long> lastReply=new HashMap<>();
        volatile Map<String,Object> view=Map.of();
        volatile boolean closed;
        volatile Set<Integer> activeUnits=Set.of(80,1,2);
        volatile long due=System.currentTimeMillis()+DRAIN_MS;
        long planned, sent, valid, skipped, timeouts, protocolErrors, abortedBlocks;
        int failures;
        int cursor;
        RuntimeState(SensorGatewayEntity config,DeviceEndpoints.Endpoint endpoint) {
            this.config=config; this.bus=new ModbusReadSession(endpoint);
            this.endpointKey=endpoint.host().toLowerCase(Locale.ROOT)+":"+endpoint.port();
        }
        void close(){ synchronized(this){closed=true;} bus.close(); }
    }
    private final SensorGatewayMapper gateways;
    private final ApplicationMapper applications;
    private final DeviceEndpoints endpoints;
    private final AgvReadService agvs;
    private final TelemetryBuffer buffer;
    private final SensorApplicationService sensorApplications;
    private final ObjectMapper json=new ObjectMapper();
    private final ConcurrentMap<Long,RuntimeState> running=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor network=new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().daemon().name("sensor-bus-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    public SensorPollingService(SensorGatewayMapper gateways, ApplicationMapper applications, DeviceEndpoints endpoints,
            AgvReadService agvs,TelemetryBuffer buffer,SensorApplicationService sensorApplications) {
        this.gateways=gateways; this.applications=applications; this.endpoints=endpoints; this.agvs=agvs; this.buffer=buffer;
        this.sensorApplications=sensorApplications;
    }
    @Scheduled(fixedDelayString="${cayleywcs.telemetry.reconcile-ms:5000}",initialDelayString="${cayleywcs.telemetry.reconcile-ms:5000}")
    public synchronized void reconcile() {
        var configs=gateways.selectList(new QueryWrapper<SensorGatewayEntity>().eq("is_valid",true).orderByAsc("id"));
        Set<Long> active=new HashSet<>();
        for(var config:configs) {
            var units=sensorApplications.activeUnits(config);
            if(units.isEmpty())continue;
            var app=applications.selectById(config.getAgv_app_id());
            if(app==null || !Boolean.TRUE.equals(app.getEnabled()) || !Boolean.TRUE.equals(app.getIs_valid())
                    || !"agv".equals(app.getDevice_type()) || !Objects.equals(app.getTenant_id(),config.getTenant_id())) continue;
            active.add(config.getId());
            RuntimeState state=running.get(config.getId());
            if(state!=null && !Objects.equals(state.config.getConfig_version(),config.getConfig_version())) {
                state.close(); running.remove(config.getId()); state=null;
            }
            if(state==null && running.size()<MAX_GATEWAYS) {
                try {
                    var endpoint=endpoints.resolveForTenant(config.getEndpoint_ref(),config.getTenant_id());
                    String key=endpoint.host().toLowerCase(Locale.ROOT)+":"+endpoint.port();
                    if(running.values().stream().anyMatch(other->other.endpointKey.equals(key))) continue;
                    state=new RuntimeState(config,endpoint);state.activeUnits=units;running.put(config.getId(),state);
                } catch(RuntimeException ignored) { /* Configuration remains visible but no socket is opened. */ }
            }
            if(state!=null)state.activeUnits=units;
        }
        running.forEach((id,state)->{if(!active.contains(id)){state.close(); running.remove(id,state);}});
    }
    public synchronized void stop(Long id) { RuntimeState state=running.remove(id); if(state!=null)state.close(); }
    public Map<String,Object> live(Long id) {
        RuntimeState state=running.get(id);
        if(state==null)return Map.of("connectionState","STOPPED");
        if(state.view.isEmpty())return Map.of("connectionState","DRAINING");
        return state.view;
    }
    @Scheduled(fixedDelayString="${cayleywcs.telemetry.tick-ms:100}")
    public void tick() {
        long now=System.currentTimeMillis();
        for(var state:running.values()) {
            if(state.closed || now<state.due || !state.busy.compareAndSet(false,true))continue;
            try { network.execute(()->{try{poll(state);}finally{state.busy.set(false);}}); }
            catch(RejectedExecutionException ex){ state.busy.set(false); state.due=now+PERIOD_MS; state.skipped++; }
        }
    }
    private void poll(RuntimeState state) {
        if(state.closed)return;
        long start=System.currentTimeMillis(); state.planned++;
        Map<String,SensorProfile.Measurement> measurements=new LinkedHashMap<>();
        Map<String,Object> diagnostics=new LinkedHashMap<>();
        boolean failed=false;
        String error="";
        var plan=SensorProfile.plan(state.config.getInfrared_offset());
        int first=state.cursor;
        for(int index=0;index<plan.size();index++) {
            int position=(first+index)%plan.size();
            var block=plan.get(position);
            if(state.closed)return;
            if(!state.activeUnits.contains(block.unit()))continue;
            Map<String,Object> context=agvs.context(state.config.getAgv_app_id());
            if(failed || System.currentTimeMillis()<state.probeAt.getOrDefault(block.name(),0L)) {
                state.abortedBlocks++;
                measurements.putAll(SensorProfile.failure(block,"UNKNOWN",System.currentTimeMillis(),state.config.getConfig_version(),context));
                continue;
            }
            try {
                state.sent++;
                int[] raw=state.bus.read(block.unit(),block.offset(),block.count());
                long received=System.currentTimeMillis();
                measurements.putAll(SensorProfile.decode(block,raw,received,state.config.getPower_on_at(),
                        state.config.getInfrared_min(),state.config.getInfrared_max(),state.config.getConfig_version(),context));
                state.valid++;
                state.blockFailures.remove(block.name()); state.probeAt.remove(block.name()); state.lastReply.put(block.unit(),received);
            } catch(InterruptedException ex) { Thread.currentThread().interrupt(); state.bus.close(); return; }
            catch(java.io.IOException | RuntimeException ex) {
                failed=true; state.bus.discardConnection();
                error=ex instanceof ModbusReadSession.InvalidReply?"PROTOCOL_ERROR":"TIMEOUT";
                if("PROTOCOL_ERROR".equals(error))state.protocolErrors++;else state.timeouts++;
                int failures=state.blockFailures.merge(block.name(),1,Integer::sum);
                state.probeAt.put(block.name(),System.currentTimeMillis()+(failures>=3?10000:Math.max(DRAIN_MS,1000L<<failures-1)));
                // Resume with another block after draining. One missing slave must not starve the other two.
                state.cursor=(position+1)%plan.size();
                measurements.putAll(SensorProfile.failure(block,error,System.currentTimeMillis(),state.config.getConfig_version(),context));
            }
        }
        if(state.closed)return;
        long end=System.currentTimeMillis();
        state.failures=failed?state.failures+1:0;
        boolean anyFresh=state.activeUnits.stream().anyMatch(unit->end-state.lastReply.getOrDefault(unit,0L)<=SensorProfile.STALE_MS);
        boolean allFresh=state.activeUnits.stream().allMatch(unit->end-state.lastReply.getOrDefault(unit,0L)<=SensorProfile.STALE_MS);
        boolean activeFailures=plan.stream().anyMatch(b->state.activeUnits.contains(b.unit()) && state.blockFailures.containsKey(b.name()));
        String connection=allFresh && !activeFailures?"ONLINE":!anyFresh && state.failures>=3?"OFFLINE":"DEGRADED";
        long slots=Math.max(1,(end-start+PERIOD_MS-1)/PERIOD_MS);
        state.skipped+=slots-1;
        state.due=failed?end+DRAIN_MS+ThreadLocalRandom.current().nextInt(251)
                :start+slots*PERIOD_MS;
        Map<String,Object> health=new LinkedHashMap<>();
        for(int unit:List.of(80,1,2)) {
            int failures=plan.stream().filter(block->block.unit()==unit).mapToInt(block->state.blockFailures.getOrDefault(block.name(),0)).max().orElse(0);
            long last=state.lastReply.getOrDefault(unit,0L);
            String status=last>0 && end-last<=SensorProfile.STALE_MS?(failures==0?"ONLINE":"DEGRADED"):failures>=3?"OFFLINE":"UNKNOWN";
            if(!state.activeUnits.contains(unit))status="STOPPED";
            health.put(Integer.toString(unit),Map.of("state",status,"consecutiveFailures",failures,"lastReplyAt",last));
        }
        diagnostics.put("plannedCycles",state.planned); diagnostics.put("sentRequests",state.sent); diagnostics.put("validResponses",state.valid);
        diagnostics.put("skippedCycles",state.skipped); diagnostics.put("consecutiveFailures",state.failures);
        diagnostics.put("generation",state.bus.generation()); diagnostics.put("error",error); diagnostics.put("nextPollAt",state.due);
        diagnostics.put("durationMs",end-start);
        diagnostics.put("timeouts",state.timeouts); diagnostics.put("protocolErrors",state.protocolErrors); diagnostics.put("abortedBlocks",state.abortedBlocks);
        @SuppressWarnings("unchecked") Map<String,Object> samples=json.convertValue(measurements,Map.class);
        for(var entry:measurements.entrySet()) if("GOOD".equals(entry.getValue().quality())) state.lastGood.put(entry.getKey(),samples.get(entry.getKey()));
        Map<String,Object> view=new LinkedHashMap<>();
        view.put("connectionState",connection); view.put("finishedAt",end); view.put("configVersion",state.config.getConfig_version());
        view.put("measurements",samples); view.put("lastGood",Map.copyOf(state.lastGood)); view.put("diagnostics",Map.copyOf(diagnostics));
        view.put("sensorHealth",health); diagnostics.put("sensorHealth",health);
        state.view=Map.copyOf(view);
        TelemetryCycleEntity cycle=new TelemetryCycleEntity(); Audits.fillCreate(cycle,null); cycle.setTenant_id(state.config.getTenant_id());
        cycle.setGateway_id(state.config.getId()); cycle.setCycle_id(UUID.randomUUID().toString()); cycle.setConfig_version(state.config.getConfig_version());
        cycle.setStarted_at(start); cycle.setFinished_at(end); cycle.setDuration_ms(end-start); cycle.setConnection_state(connection);
        cycle.setMeasurements(samples); cycle.setDiagnostics(diagnostics);
        synchronized(state){ if(!state.closed) buffer.offer(cycle); }
    }
    @PreDestroy public synchronized void close() { running.values().forEach(RuntimeState::close); running.clear(); network.shutdownNow(); }
}
