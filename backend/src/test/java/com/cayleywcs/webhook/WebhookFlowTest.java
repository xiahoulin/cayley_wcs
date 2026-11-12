package com.cayleywcs.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.task.TaskService;
import com.cayleywcs.task.TaskStatus;
import com.cayleywcs.task.entity.TaskEntity;
import com.cayleywcs.task.mapper.TaskMapper;
import com.cayleywcs.webhook.entity.WebhookDeliveryEntity;
import com.cayleywcs.webhook.entity.WebhookEntity;
import com.cayleywcs.webhook.mapper.WebhookDeliveryMapper;
import com.cayleywcs.webhook.mapper.WebhookMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M13 Webhook：游标扫描（分类/scope/去重/游标推进）、投递（内嵌 HTTP server：先失败后成功验证退避+签名头）、
 * SSRF 校验、CRUD 校验。
 */
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:cayleywcs_webhook_flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "cayleywcs.webhook.scan-interval-ms=3600000",
        "cayleywcs.webhook.deliver-interval-ms=3600000"})
@ActiveProfiles("test-flyway")
class WebhookFlowTest {
    @Autowired private com.cayleywcs.protocol.ProtocolService fixtureProtocols;
    @Autowired private com.cayleywcs.application.mapper.ApplicationMapper fixtureApps;


    @Autowired
    private WebhookService webhookService;
    @Autowired
    private WebhookScanner scanner;
    @Autowired
    private WebhookDeliveryWorker worker;
    @Autowired
    private WebhookDeliveryMapper deliveryMapper;
    @Autowired
    private WebhookMapper webhookMapper;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskMapper taskMapper;

    /** 直插 webhook（绕过管理端 create 的登录用户要求；scanner/worker 只需 webhook 行存在）。 */
    private WebhookEntity registerWebhook(String url, List<String> events, List<Long> scope) {
        WebhookEntity wh = new WebhookEntity();
        wh.setName("test-" + System.nanoTime());
        wh.setUrl(url);
        wh.setSecret("s3cr3t");
        wh.setEvent_types(events);
        wh.setScope_app_ids(scope);
        wh.setEnabled(true);
        wh.setRemark("");
        wh.setCreator("test");
        wh.setCreate_time(LocalDateTime.now());
        wh.setLast_update_time(LocalDateTime.now());
        wh.setIs_valid(true);
        wh.setTenant_id(1L);
        webhookMapper.insert(wh);
        return wh;
    }

    private TaskEntity completedTask(long appId, String no) {
        TaskEntity t = new TaskEntity();
        t.setApp_id(appId);
        t.setTask_no(no);
        t.setTask_type("1");
        com.cayleywcs.support.PublishedDeviceFixtures.ensure(fixtureProtocols,fixtureApps,t.getApp_id(),"stacker");
        t.setPayload(com.cayleywcs.support.PublishedDeviceFixtures.stackerPayload());
        t = taskService.create(t);
        taskMapper.update(null, new UpdateWrapper<TaskEntity>()
                .eq("\"id\"", t.getId()).set("\"status\"", TaskStatus.COMPLETED)
                .set("\"last_update_time\"", LocalDateTime.now())); // 完成即推进 lut（镜像真实握手 save）
        return taskService.getById(t.getId());
    }

    @Test
    void scannerEmitsDeliveryForSubscribedTerminalEvent() {
        WebhookEntity wh = registerWebhook("http://127.0.0.1:1/hook",
                List.of("task.completed"), List.of(95001L));
        TaskEntity done = completedTask(95001L, "WH-1");

        scanner.runOnce();

        List<WebhookDeliveryEntity> ds = deliveryMapper.selectList(new QueryWrapper<WebhookDeliveryEntity>()
                .eq("\"webhook_id\"", wh.getId()).eq("\"biz_id\"", done.getId()));
        assertThat(ds).hasSize(1);
        assertThat(ds.get(0).getEvent_type()).isEqualTo("task.completed");
        assertThat(ds.get(0).getStatus()).isEqualTo("pending");
        assertThat(ds.get(0).getPayload()).contains("\"taskNo\":\"WH-1\"");

        // 幂等：再扫不重复（游标已过 + 唯一键）
        scanner.runOnce();
        assertThat(deliveryMapper.selectCount(new QueryWrapper<WebhookDeliveryEntity>()
                .eq("\"webhook_id\"", wh.getId()).eq("\"biz_id\"", done.getId()))).isEqualTo(1);
    }

    @Test
    void scannerRespectsScopeAndEventSubscription() {
        WebhookEntity wh = registerWebhook("http://127.0.0.1:1/hook",
                List.of("task.completed"), List.of(95010L)); // 仅 app 95010
        TaskEntity inScope = completedTask(95010L, "WH-IN");
        TaskEntity outScope = completedTask(95011L, "WH-OUT");
        // 一个 pending 任务（非终态，不外发）
        TaskEntity pending = new TaskEntity();
        pending.setApp_id(95010L);
        pending.setTask_no("WH-PENDING");
        pending.setPayload(com.cayleywcs.support.PublishedDeviceFixtures.stackerPayload());
        taskService.create(pending);

        scanner.runOnce();

        assertThat(hasDelivery(wh.getId(), inScope.getId())).isTrue();
        assertThat(hasDelivery(wh.getId(), outScope.getId())).as("scope 外不外发").isFalse();
        assertThat(hasDelivery(wh.getId(), pending.getId())).as("非终态不外发").isFalse();
    }

    @Test
    void cursorAdvancesAndSkipsPreRegistrationHistory() throws InterruptedException {
        // 注册前完成的任务：不回放（游标起点=订阅时刻）
        TaskEntity historical = completedTask(95040L, "WH-HIST");
        Thread.sleep(2); // 确保 webhook.create_time 严格晚于 historical.lut
        WebhookEntity wh = registerWebhook("http://127.0.0.1:1/hook", List.of("task.completed"), List.of(95040L));
        Thread.sleep(2);
        TaskEntity fresh1 = completedTask(95040L, "WH-FRESH1");

        scanner.runOnce();
        assertThat(hasDelivery(wh.getId(), historical.getId())).as("注册前历史不回放").isFalse();
        assertThat(hasDelivery(wh.getId(), fresh1.getId())).as("注册后事件投递").isTrue();

        // 游标已推进过 fresh1：第二轮扫到更新的任务（证明游标前进、不卡在首批）
        Thread.sleep(2);
        TaskEntity fresh2 = completedTask(95040L, "WH-FRESH2");
        scanner.runOnce();
        assertThat(hasDelivery(wh.getId(), fresh2.getId())).as("游标推进后续投递").isTrue();
    }

    @Test
    void deliveryWorkerPostsSignedAndRetriesUntilSuccess() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        StringBuilder lastSign = new StringBuilder();
        StringBuilder lastBody = new StringBuilder();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            int n = hits.incrementAndGet();
            byte[] body = ex.getRequestBody().readAllBytes();
            lastBody.setLength(0);
            lastBody.append(new String(body, StandardCharsets.UTF_8));
            lastSign.setLength(0);
            lastSign.append(ex.getRequestHeaders().getFirst("X-Webhook-Sign"));
            int code = n < 2 ? 500 : 200; // 第一次失败、第二次成功
            ex.sendResponseHeaders(code, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            WebhookEntity wh = registerWebhook("http://127.0.0.1:" + port + "/hook",
                    List.of("task.completed"), List.of(95020L)); // 仅本测试的 app，避免捕获他测已完成任务
            TaskEntity done = completedTask(95020L, "WH-DELIVER");
            scanner.runOnce();
            // 明确进入待投递窗口，避免数据库时间精度与当前时间的边界影响本次HTTP断言。
            WebhookDeliveryEntity due = deliveryFor(wh.getId(), done.getId());
            deliveryMapper.update(null, new UpdateWrapper<WebhookDeliveryEntity>()
                    .eq("\"id\"", due.getId()).set("\"next_retry_time\"", LocalDateTime.now().minusSeconds(1)));

            // 第一次投递：500 → 退避（attempts=1, 仍 pending, next_retry 未来）
            worker.runOnce();
            WebhookDeliveryEntity d = deliveryFor(wh.getId(), done.getId());
            assertThat(hits.get()).isEqualTo(1);
            assertThat(d.getStatus()).isEqualTo("pending");
            assertThat(d.getAttempts()).isEqualTo(1L);
            assertThat(d.getNext_retry_time()).isAfter(LocalDateTime.now());
            // 验证签名头 = HMAC(secret, ts+nonce+sha256(body)) —— 服务端能复算（此处仅断言非空）
            assertThat(lastSign.toString()).isNotBlank();
            assertThat(lastBody.toString()).contains("task.completed");

            // 把 next_retry_time 提前到过去，模拟退避到点 → 第二次投递成功
            deliveryMapper.update(null, new UpdateWrapper<WebhookDeliveryEntity>()
                    .eq("\"id\"", d.getId()).set("\"next_retry_time\"", LocalDateTime.now().minusMinutes(1)));
            worker.runOnce();
            d = deliveryFor(wh.getId(), done.getId());
            assertThat(hits.get()).isEqualTo(2);
            assertThat(d.getStatus()).isEqualTo("ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ssrfAndUrlValidation() {
        assertThat(worker.validateUrl("https://ok.example.com/hook")).isNull();
        assertThat(worker.validateUrl("ftp://evil/x")).contains("http/https");
        assertThat(worker.validateUrl("file:///etc/passwd")).contains("http/https");
        assertThat(worker.validateUrl("not a url")).isNotNull();
        assertThat(worker.validateUrl("")).isNotNull();
        // 管理端 create 即拦截非法 URL（validate 在 currentUser 之前，无需登录上下文）
        WebhookEntity bad = new WebhookEntity();
        bad.setName("bad");
        bad.setUrl("ftp://evil/x");
        bad.setEvent_types(List.of("task.completed"));
        bad.setScope_app_ids(List.of());
        assertThatThrownBy(() -> webhookService.create(bad)).isInstanceOf(WcsException.class);
    }

    @Test
    void failedDeliveryStopsAfterMaxAttempts() {
        // 无监听端口 → 连接必失败；把 next_retry 反复提前，验证最终 failed
        WebhookEntity wh = registerWebhook("http://127.0.0.1:1/hook", // 端口 1 不可连
                List.of("task.completed"), List.of(95030L));
        TaskEntity done = completedTask(95030L, "WH-FAIL");
        scanner.runOnce();
        for (int i = 0; i < 10; i++) {
            WebhookDeliveryEntity d = deliveryFor(wh.getId(), done.getId());
            if ("failed".equals(d.getStatus())) {
                break;
            }
            deliveryMapper.update(null, new UpdateWrapper<WebhookDeliveryEntity>()
                    .eq("\"id\"", d.getId()).set("\"next_retry_time\"", LocalDateTime.now().minusMinutes(1)));
            worker.runOnce();
        }
        assertThat(deliveryFor(wh.getId(), done.getId()).getStatus()).isEqualTo("failed");
        assertThat(deliveryFor(wh.getId(), done.getId()).getAttempts()).isGreaterThanOrEqualTo(6L);
    }

    private boolean hasDelivery(Long webhookId, Long bizId) {
        return deliveryMapper.selectCount(new QueryWrapper<WebhookDeliveryEntity>()
                .eq("\"webhook_id\"", webhookId).eq("\"biz_id\"", bizId)) > 0;
    }

    private WebhookDeliveryEntity deliveryFor(Long webhookId, Long bizId) {
        return deliveryMapper.selectOne(new QueryWrapper<WebhookDeliveryEntity>()
                .eq("\"webhook_id\"", webhookId).eq("\"biz_id\"", bizId).last("limit 1"));
    }
}
