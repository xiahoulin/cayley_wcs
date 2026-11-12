package com.cayleywcs.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.cayleywcs.webhook.entity.WebhookDeliveryEntity;
import com.cayleywcs.webhook.entity.WebhookEntity;
import com.cayleywcs.webhook.mapper.WebhookDeliveryMapper;
import com.cayleywcs.webhook.mapper.WebhookMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebhookShutdownTest {
    @Test
    void interruptionRecordsCurrentAttemptAndDoesNotStartNextDelivery() throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.createContext("/hook", exchange -> {
            if (hits.incrementAndGet() == 1) {
                firstArrived.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();

        WebhookDeliveryMapper deliveries = mock(WebhookDeliveryMapper.class);
        WebhookMapper webhooks = mock(WebhookMapper.class);
        WebhookEntity webhook = new WebhookEntity();
        webhook.setId(1L);
        webhook.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
        webhook.setSecret("secret");
        webhook.setEnabled(true);
        webhook.setIs_valid(true);
        WebhookDeliveryEntity first = delivery(1L);
        WebhookDeliveryEntity second = delivery(2L);
        when(deliveries.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(deliveries.updateById(any(WebhookDeliveryEntity.class))).thenReturn(1);
        when(webhooks.selectById(1L)).thenReturn(webhook);
        WebhookDeliveryWorker worker = new WebhookDeliveryWorker(
                deliveries, webhooks, 6, 30000, 1800000, 5000, "", false);
        Thread thread = new Thread(worker::runOnce);
        try {
            thread.start();
            assertThat(firstArrived.await(3, TimeUnit.SECONDS)).isTrue();
            thread.interrupt();
            thread.join(3000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(hits.get()).isEqualTo(1);
            assertThat(first.getAttempts()).isEqualTo(1L);
            assertThat(first.getStatus()).isEqualTo("pending");
            assertThat(second.getAttempts()).isZero();
            verify(deliveries).updateById(first);
        } finally {
            releaseFirst.countDown();
            thread.interrupt();
            thread.join(3000);
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static WebhookDeliveryEntity delivery(long id) {
        WebhookDeliveryEntity row = new WebhookDeliveryEntity();
        row.setId(id);
        row.setWebhook_id(1L);
        row.setPayload("{}");
        row.setAttempts(0L);
        row.setStatus("pending");
        row.setNext_retry_time(LocalDateTime.now().minusSeconds(1));
        row.setIs_valid(true);
        return row;
    }
}
