package com.cayleywcs.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cayleywcs.adapter.ProtocolAdapterFactory;
import com.cayleywcs.adapter.loopback.LoopbackAdapterProvider;
import com.cayleywcs.application.ApplicationService;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.common.exception.ErrorCode;
import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.protocol.ProtocolService;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 连接治理单测：建连成功 / 满槽拒绝 / 60s 超时回收连接槽。
 */
class ConnectionManagerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProtocolAdapterFactory factory =
            new ProtocolAdapterFactory(List.of(new LoopbackAdapterProvider()));
    private final ApplicationService appService = mock(ApplicationService.class);
    private final ProtocolService protoService = mock(ProtocolService.class);

    @Test
    void pointIndexRejectsAmbiguousDuplicateAndCopiesUniqueDefinitions() {
        ProtocolPointEntity first = new ProtocolPointEntity();
        first.setField_name("status_Task");
        first.setAddress("first");
        ProtocolPointEntity duplicate = new ProtocolPointEntity();
        duplicate.setField_name("status_Task");
        duplicate.setAddress("later");
        List<ProtocolPointEntity> points = new java.util.ArrayList<>(List.of(first, duplicate));
        assertThatThrownBy(()->new ManagedConnection(new ApplicationEntity(),new ProtocolEntity(),points,null))
                .isInstanceOf(WcsException.class).hasMessageContaining("重复点位");
        points.remove(duplicate);
        ManagedConnection connection = new ManagedConnection(new ApplicationEntity(), new ProtocolEntity(), points, null);

        assertThat(connection.point("status_Task")).isSameAs(first);
        assertThat(connection.point("missing")).isNull();
        assertThat(connection.points()).containsExactly(first);
        points.remove(first);
        assertThat(connection.point("status_Task")).isSameAs(first);
        assertThat(connection.points()).containsExactly(first);
    }

    @Test
    void openSucceedsAndOccupiesSlot() {
        wire(1L, "{}");
        ConnectionManager manager = manager(4, 5);

        ConnectionSnapshot snap = manager.open(1L);

        assertThat(snap.state()).isEqualTo(ConnectionState.RUNNING.name());
        assertThat(manager.slotUsage().used()).isEqualTo(1);
        assertThat(manager.isOpen(1L)).isTrue();
        manager.shutdown();
    }

    @Test
    void poolFullRejectsWithWaitHint() {
        wire(1L, "{}");
        wire(2L, "{}");
        ConnectionManager manager = manager(1, 5);

        manager.open(1L);

        assertThatThrownBy(() -> manager.open(2L))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode())
                        .isEqualTo(ErrorCode.CONN_POOL_FULL.code()));
        manager.shutdown();
    }

    @Test
    void connectTimeoutReclaimsSlot() {
        wire(1L, "{\"connectDelayMs\":3000}"); // 建连耗时 3s > 1s 超时
        wire(2L, "{}");
        ConnectionManager manager = manager(1, 1);

        assertThatThrownBy(() -> manager.open(1L))
                .isInstanceOf(WcsException.class)
                .satisfies(ex -> assertThat(((WcsException) ex).getCode())
                        .isEqualTo(ErrorCode.CONN_TIMEOUT.code()));

        assertThat(manager.slotUsage().used()).isEqualTo(0); // 槽位已回收
        ConnectionSnapshot snap = manager.open(2L);
        assertThat(snap.state()).isEqualTo(ConnectionState.RUNNING.name());
        manager.shutdown();
    }

    private ConnectionManager manager(int max, int timeoutSeconds) {
        return new ConnectionManager(appService, protoService, factory, event -> { }, null,
                max, timeoutSeconds, 12000L, 5, 0L);
    }

    @Test
    void shutdownPreservesDesiredOpenIntent() {
        wire(1L,"{}"); ConnectionManager manager=manager(2,5); manager.open(1L);
        org.mockito.Mockito.clearInvocations(appService);
        manager.shutdown();
        org.mockito.Mockito.verify(appService,org.mockito.Mockito.never()).updateDesiredState(1L,"closed");
        assertThat(manager.isOpen(1L)).isFalse();
        assertThat(manager.slotUsage().used()).isZero();
    }

    @Test
    void foreignOwnedConnectionCannotBeReadOrClosed() {
        wire(1L,"{}"); ConnectionManager manager=manager(2,5); manager.open(1L);
        org.springframework.test.util.ReflectionTestUtils.setField(manager,"contracts",mock(com.cayleywcs.protocol.contract.ProtocolContractService.class));
        when(appService.getById(1L)).thenThrow(new WcsException(ErrorCode.NOT_FOUND,"不可访问"));
        assertThatThrownBy(()->manager.close(1L)).isInstanceOf(WcsException.class);
        assertThatThrownBy(()->manager.snapshot(1L)).isInstanceOf(WcsException.class);
        assertThat(manager.isOpen(1L)).isTrue();
        assertThat(manager.snapshots()).isEmpty();
        manager.shutdown();
    }

    private void wire(long appId, String connParamsJson) {
        ApplicationEntity app = new ApplicationEntity();
        app.setId(appId);
        app.setApp_code("APP" + appId);
        app.setApp_name("app" + appId);
        app.setProtocol_id(100L);
        app.setHeartbeat_interval_ms(60000L); // 大间隔，避免 worker 干扰断言
        app.setConn_params(parse(connParamsJson));

        ProtocolEntity protocol = new ProtocolEntity();
        protocol.setId(100L);
        protocol.setProtocol_code("LOOP");
        protocol.setProtocol_type("loopback");

        ProtocolPointEntity hb = new ProtocolPointEntity();
        hb.setField_name("WCS_Heart");
        hb.setData_type("INT");
        hb.setDirection("request");
        hb.setPoint_scope("internal");
        ProtocolPointEntity mode = new ProtocolPointEntity();
        mode.setField_name("status_Mode");
        mode.setData_type("INT");
        mode.setDirection("response");
        mode.setPoint_scope("business");

        when(appService.validateForConnect(appId)).thenReturn(app);
        lenient().when(protoService.getById(100L)).thenReturn(protocol);
        lenient().when(protoService.listPoints(100L)).thenReturn(List.of(hb, mode));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
