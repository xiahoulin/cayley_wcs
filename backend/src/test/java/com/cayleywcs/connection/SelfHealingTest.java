package com.cayleywcs.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolAdapter;
import com.cayleywcs.adapter.ProtocolAdapterFactory;
import com.cayleywcs.adapter.ProtocolAdapterProvider;
import com.cayleywcs.alarm.AlarmService;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M12 单机自愈：desired_state 落库、启动恢复（reconcileOnce 建连）、看门狗 rearm（FAILED+desired=open 冷却后重连）、
 * FAILED+desired≠open 释放槽位。用可控 FlakyAdapter 精确驱动连接成败。
 */
class SelfHealingTest {
    private static final long APP_ID = 1L;
    private static final long PROTOCOL_ID = 200L;

    @Test
    void openPersistsDesiredOpenAndClosePersistsDesiredClosed() {
        Fixture f = new Fixture("closed");
        ConnectionManager manager = f.manager(60000L);
        manager.open(APP_ID);
        verify(f.appService).updateDesiredState(APP_ID, "open");
        manager.close(APP_ID);
        verify(f.appService).updateDesiredState(APP_ID, "closed");
        manager.shutdown();
    }

    @Test
    void recoveryRunnerOpensDesiredOpenApps() {
        Fixture f = new Fixture("open");
        ConnectionManager manager = f.manager(60000L);
        ConnectionRecoveryRunner runner = new ConnectionRecoveryRunner(f.appService, manager, mock(AlarmService.class));

        assertThat(manager.isOpen(APP_ID)).isFalse();
        int opened = runner.reconcileOnce();

        assertThat(opened).isEqualTo(1);
        assertThat(manager.isOpen(APP_ID)).isTrue();
        assertThat(manager.snapshot(APP_ID).state()).isEqualTo(ConnectionState.RUNNING.name());
        manager.shutdown();
    }

    @Test
    void recoveryRunnerSkipsDesiredClosed() {
        Fixture f = new Fixture("closed");
        ConnectionManager manager = f.manager(60000L);
        ConnectionRecoveryRunner runner = new ConnectionRecoveryRunner(f.appService, manager, mock(AlarmService.class));
        assertThat(runner.reconcileOnce()).isZero();
        assertThat(manager.isOpen(APP_ID)).isFalse();
        manager.shutdown();
    }

    @Test
    void watchdogRearmsFailedWhenDesiredOpen() {
        Fixture f = new Fixture("open");
        ConnectionManager manager = f.manager(0L); // cooldown=0：FAILED 立即可 rearm
        manager.open(APP_ID);
        assertThat(manager.snapshot(APP_ID).state()).isEqualTo(ConnectionState.RUNNING.name());

        // 断连：后续重连失败 → 心跳失活判 stale → 重连耗尽 → FAILED
        f.adapter.failConnect = true;
        forceStale();
        manager.runWatchdogOnce(); // RUNNING→DISCONNECTED→attemptReconnect(第1次失败)→DISCONNECTED
        manager.runWatchdogOnce(); // attemptReconnect(retry耗尽)→FAILED（记 lastFailedAt）
        assertThat(manager.snapshot(APP_ID).state()).isEqualTo(ConnectionState.FAILED.name());

        // 设备恢复 + cooldown=0 → 看门狗 rearm 回 RUNNING
        f.adapter.failConnect = false;
        manager.runWatchdogOnce(); // FAILED+desired=open+冷却到点 → DISCONNECTED（rearm，retry 清零）
        manager.runWatchdogOnce(); // DISCONNECTED → attemptReconnect → RUNNING
        assertThat(manager.snapshot(APP_ID).state()).isEqualTo(ConnectionState.RUNNING.name());
        manager.shutdown();
    }

    @Test
    void watchdogReleasesSlotWhenFailedAndDesiredNotOpen() {
        Fixture f = new Fixture("open");
        ConnectionManager manager = f.manager(0L);
        manager.open(APP_ID); // open 落 desired=open
        f.adapter.failConnect = true;
        forceStale();
        manager.runWatchdogOnce();
        manager.runWatchdogOnce(); // → FAILED
        assertThat(manager.snapshot(APP_ID).state()).isEqualTo(ConnectionState.FAILED.name());
        assertThat(manager.slotUsage().used()).isEqualTo(1);

        // 外部把期望态改 closed（app 停用/直接改期望态，非经 close()）：看门狗释放该 FAILED 连接
        f.app.setDesired_state("closed");
        manager.runWatchdogOnce(); // FAILED + desired=closed → 释放槽位并移除
        assertThat(manager.isOpen(APP_ID)).isFalse();
        assertThat(manager.slotUsage().used()).isZero();
        manager.shutdown();
    }

    @Test
    void workerDoesNotBounceFailedConnectionStarvingRearm() throws Exception {
        // 对抗审查 P1：worker 轮询（小心跳间隔）+ FAILED——修复前 worker 把 FAILED 打回 DISCONNECTED，
        // 看门狗 attemptReconnect 耗尽路径每轮 markFailed(now) 刷新 lastFailedAt，rearm 永久饿死。
        // 修复后 pollOnce 对非 RUNNING/CONNECTED 早退，FAILED 保持稳定。
        Fixture f = new Fixture("open", 300L); // worker 每 ~300ms 轮询
        // maxRetry=0：DISCONNECTED 一进 attemptReconnect 即 FAILED；cooldown 大：本测试不期望 rearm 触发
        ConnectionManager manager = new ConnectionManager(f.appService, f.protoService,
                new ProtocolAdapterFactory(List.of(f.provider())), event -> { }, null,
                4, 1, 1000L, 0, 0L, 100000L);
        manager.open(APP_ID);
        f.adapter.killed = true;      // 设备失联
        f.adapter.failConnect = true; // 重连必败
        Thread.sleep(600);            // 等 worker 轮询把 RUNNING 打到 DISCONNECTED
        manager.runWatchdogOnce();    // DISCONNECTED → attemptReconnect(maxRetry0) → FAILED
        // 等 FAILED 稳定
        long deadline = System.currentTimeMillis() + 2000;
        while (!ConnectionState.FAILED.name().equals(manager.snapshot(APP_ID).state())
                && System.currentTimeMillis() < deadline) {
            manager.runWatchdogOnce();
            Thread.sleep(50);
        }
        assertThat(manager.snapshot(APP_ID).state()).isEqualTo(ConnectionState.FAILED.name());

        // 关键：worker 连续轮询 ~5 轮，FAILED 不得被打回 DISCONNECTED（修复前必然横跳）
        Thread.sleep(1500);
        assertThat(manager.snapshot(APP_ID).state())
                .as("worker 不得把 FAILED 打回 DISCONNECTED").isEqualTo(ConnectionState.FAILED.name());
        manager.shutdown();
    }

    /** heartbeatStaleMs 被 clamp 到最小 1000ms，睡 1.2s 使心跳失活可被看门狗判 stale。 */
    private static void forceStale() {
        try {
            Thread.sleep(1200);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 可切换连接成败的适配器（单实例共享，重连复用同一实例）。killed=设备失联（isConnected 立即 false）。 */
    private static final class FlakyAdapter implements ProtocolAdapter {
        volatile boolean failConnect = false;
        volatile boolean killed = false;
        private volatile boolean connected = false;

        @Override public String protocolType() {
            return "flaky";
        }

        @Override public void connect(AdapterContext ctx) {
            if (failConnect) {
                throw new IllegalStateException("connect refused (test)");
            }
            connected = true;
        }

        @Override public void disconnect() {
            connected = false;
        }

        @Override public boolean isConnected() {
            return connected && !killed;
        }

        @Override public boolean heartbeat() {
            return connected;
        }

        @Override public Map<String, Object> readAll(List<ProtocolPointEntity> points) {
            return Map.of();
        }

        @Override public Object read(ProtocolPointEntity point) {
            return 0;
        }

        @Override public void write(ProtocolPointEntity point, Object value) {
            // no-op
        }
    }

    private static final class Fixture {
        final ApplicationService appService = mock(ApplicationService.class);
        final ProtocolService protoService = mock(ProtocolService.class);
        final FlakyAdapter adapter = new FlakyAdapter();
        final ApplicationEntity app = new ApplicationEntity();

        Fixture(String desiredState) {
            this(desiredState, 60000L);
        }

        Fixture(String desiredState, long heartbeatMs) {
            app.setId(APP_ID);
            app.setApp_code("HEAL");
            app.setApp_name("自愈");
            app.setProtocol_id(PROTOCOL_ID);
            app.setDevice_type("stacker");
            app.setEnabled(true);
            app.setDesired_state(desiredState);
            app.setHeartbeat_interval_ms(heartbeatMs);

            ProtocolEntity protocol = new ProtocolEntity();
            protocol.setId(PROTOCOL_ID);
            protocol.setProtocol_code("HEAL");
            protocol.setProtocol_type("flaky");

            when(appService.validateForConnect(APP_ID)).thenReturn(app);
            lenient().when(appService.getById(APP_ID)).thenReturn(app);
            lenient().when(appService.listAll()).thenReturn(List.of(app));
            // 有状态 mock：updateDesiredState 真的改 app.desired_state（镜像真实 DB），使 open() 的
            // 「建连后复核 desired」在无并发 close 时读到自己刚落的 open 而非陈旧值。
            lenient().doAnswer(inv -> {
                app.setDesired_state(inv.getArgument(1));
                return null;
            }).when(appService).updateDesiredState(org.mockito.ArgumentMatchers.eq(APP_ID),
                    org.mockito.ArgumentMatchers.anyString());
            lenient().when(protoService.getById(PROTOCOL_ID)).thenReturn(protocol);
            lenient().when(protoService.listPoints(PROTOCOL_ID)).thenReturn(List.of());
        }

        ProtocolAdapterProvider provider() {
            return new ProtocolAdapterProvider() {
                @Override public String protocolType() {
                    return "flaky";
                }

                @Override public ProtocolAdapter create(AdapterContext ctx) {
                    return adapter;
                }
            };
        }

        ConnectionManager manager(long rearmCooldownMs) {
            ProtocolAdapterFactory factory = new ProtocolAdapterFactory(List.of(provider()));
            // heartbeatStale=1000(clamp下限), reconnectMaxRetry=1（rearm 后能真正重连一次）
            return new ConnectionManager(appService, protoService, factory, event -> { }, null,
                    4, 1, 1000L, 1, 0L, rearmCooldownMs);
        }
    }
}
