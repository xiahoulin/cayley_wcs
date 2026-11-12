package com.cayleywcs.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.context;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.point;

import com.cayleywcs.common.exception.WcsException;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 响应采集与驱动操作采用方向和用途，任务号读回不依赖额外权限配置。 */
class AdapterPointDirectionTest {
    @Test void pollingCollectsBusinessAndInternalResponsesWithoutReadingRequests() throws Exception {
        var command=point("cmd_TaskType","command","INT16","request");
        var nativeNumber=point("cmd_TaskNum","native-number","UINT16","request");
        nativeNumber.setPoint_scope("internal");
        var state=point("status_Task","state","INT16","response");
        var heartbeat=point("PLC_Heart","heart","INT16","response");
        heartbeat.setPoint_scope("internal");
        var adapter=new RecordingAdapter();
        var points=List.of(command,nativeNumber,state,heartbeat);
        adapter.connect(context("http",points,Map.of()));
        try {
            assertThat(adapter.readAll(points)).containsOnlyKeys("status_Task","PLC_Heart");
            assertThat(adapter.reads).containsExactly("status_Task","PLC_Heart");
            adapter.write(nativeNumber,17);
            assertThat(adapter.read(nativeNumber)).isEqualTo(17L);
            assertThat(adapter.reads).endsWith("cmd_TaskNum");
        } finally { adapter.disconnect(); }
    }

    @Test void removalOfPointPermissionsStillRequiresAConnection() {
        var adapter=new RecordingAdapter();
        var request=point("cmd_TaskNum","number","UINT16","request");
        request.setPoint_scope("internal");
        assertThatThrownBy(()->adapter.read(request))
                .isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4222));
        assertThatThrownBy(()->adapter.write(request,1))
                .isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4222));
        assertThat(adapter.reads).isEmpty();
    }

    private static final class RecordingAdapter extends AbstractProtocolAdapter {
        private final List<String> reads=new ArrayList<>();
        private final Map<String,Object> values=new java.util.HashMap<>();
        @Override public String protocolType() {return "http";}
        @Override protected void doConnect(AdapterContext context) { }
        @Override protected void doDisconnect() { }
        @Override public boolean heartbeat() {return isConnected();}
        @Override public Object read(ProtocolPointEntity point) {
            requireConnected(); reads.add(point.getField_name());
            return decodeResponse(point,values.getOrDefault(point.getField_name(),0L));
        }
        @Override public void write(ProtocolPointEntity point,Object value) {
            requireConnected(); values.put(point.getField_name(),encodeValue(point,value));
        }
    }
}
