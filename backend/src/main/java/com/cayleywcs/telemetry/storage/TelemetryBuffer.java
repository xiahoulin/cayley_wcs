package com.cayleywcs.telemetry.storage;

import com.cayleywcs.telemetry.entity.TelemetryCycleEntity;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Per-gateway bounded queues; slow database writes never run on a network worker. */
@Component
public class TelemetryBuffer {
    private static final int CAPACITY=60;
    private static final class Inbox {
        final ArrayBlockingQueue<TelemetryCycleEntity> queue=new ArrayBlockingQueue<>(CAPACITY);
        final AtomicBoolean busy=new AtomicBoolean();
        final AtomicLong dropped=new AtomicLong();
        volatile String persistence="OK";
    }
    private final ConcurrentMap<Long,Inbox> inboxes=new ConcurrentHashMap<>();
    private final ExecutorService writers=Executors.newFixedThreadPool(2,Thread.ofPlatform().daemon().name("telemetry-store-",0).factory());
    private final TelemetryStorageService storage;
    public TelemetryBuffer(TelemetryStorageService storage) { this.storage=storage; }
    public void offer(TelemetryCycleEntity cycle) {
        Inbox inbox=inboxes.computeIfAbsent(cycle.getGateway_id(),ignored->new Inbox());
        if (!inbox.queue.offer(cycle)) inbox.dropped.incrementAndGet();
    }
    public Map<String,Object> status(Long gatewayId) {
        Inbox inbox=inboxes.get(gatewayId);
        return inbox==null?Map.of("queued",0,"dropped",0,"persistence","OK"):
                Map.of("queued",inbox.queue.size(),"dropped",inbox.dropped.get(),"persistence",inbox.persistence);
    }
    public void forget(Long gatewayId) {
        Inbox inbox=inboxes.remove(gatewayId);
        if(inbox!=null) inbox.queue.clear();
    }
    @Scheduled(fixedDelayString="${cayleywcs.telemetry.storage-tick-ms:250}")
    public void flush() {
        for (Inbox inbox:inboxes.values()) {
            if (inbox.queue.isEmpty() || !inbox.busy.compareAndSet(false,true)) continue;
            try { writers.execute(()->{
                try {
                    for(int i=0;i<10;i++) {
                        TelemetryCycleEntity cycle=inbox.queue.peek();
                        if(cycle==null)break;
                        storage.save(cycle); inbox.queue.poll(); inbox.persistence="OK";
                    }
                } catch(RuntimeException ex) { inbox.persistence="RETRYING"; }
                finally { inbox.busy.set(false); }
            }); } catch(RejectedExecutionException ex) { inbox.busy.set(false); }
        }
    }
    @PreDestroy public void close() { writers.shutdownNow(); }
}
