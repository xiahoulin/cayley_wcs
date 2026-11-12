package com.cayleywcs.agv;

import com.cayleywcs.telemetry.entity.TelemetryCycleEntity;
import com.cayleywcs.telemetry.storage.TelemetryBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TelemetryBufferTest {
    @Test void slowFailingStorageCannotGrowQueueOrBlockAcquisition() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        TelemetryBuffer buffer=new TelemetryBuffer(cycle->{
            entered.countDown();
            try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            throw new IllegalStateException("database unavailable");
        });
        try {
            for(int i=0;i<60;i++)buffer.offer(cycle());
            buffer.flush();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            long start=System.nanoTime();
            for(int i=0;i<100;i++)buffer.offer(cycle());
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(300);
            assertThat(buffer.status(1L)).containsEntry("queued",60).containsEntry("dropped",100L);
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!"RETRYING".equals(buffer.status(1L).get("persistence")) && System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(buffer.status(1L)).containsEntry("persistence","RETRYING");
            assertThat(buffer.status(1L)).containsEntry("queued",60);
        } finally {release.countDown();buffer.close();}
    }
    private static TelemetryCycleEntity cycle() {var cycle=new TelemetryCycleEntity();cycle.setGateway_id(1L);return cycle;}
}
