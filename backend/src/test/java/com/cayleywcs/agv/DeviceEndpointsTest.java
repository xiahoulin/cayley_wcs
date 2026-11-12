package com.cayleywcs.agv;

import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DeviceEndpointsTest {
    @Test void configuredEndpointCannotBeReusedByAnotherTenant() {
        var endpoints=new DeviceEndpoints(new MockEnvironment()
                .withProperty("cayleywcs.endpoints.vehicle.host","127.0.0.1")
                .withProperty("cayleywcs.endpoints.vehicle.port","12345"));
        assertThat(endpoints.resolveForTenant("vehicle",1L).port()).isEqualTo(12345);
        assertThatThrownBy(()->endpoints.resolveForTenant("vehicle",2L)).hasMessageContaining("不可访问");
    }
    @Test void dormantReferenceAlsoHasAnOwnerAndCannotBeClaimedByOtherTenant() {
        var endpoints=new DeviceEndpoints(new MockEnvironment().withProperty("cayleywcs.endpoints.future-bus.tenant-id","2"));
        assertThat(endpoints.findForTenant("future-bus",2L)).isEmpty();
        assertThatThrownBy(()->endpoints.findForTenant("future-bus",1L)).hasMessageContaining("不可访问");
        assertThatThrownBy(()->endpoints.resolveForTenant("future-bus",2L)).hasMessageContaining("尚未配置");
    }
    @Test void malformedReferencesAndPartialNetworkConfigurationAreRejected() {
        var endpoints=new DeviceEndpoints(new MockEnvironment().withProperty("cayleywcs.endpoints.bad.host","127.0.0.1"));
        assertThatThrownBy(()->endpoints.findForTenant("../bad",1L)).hasMessageContaining("端点引用");
        assertThatThrownBy(()->endpoints.findForTenant("bad",1L)).hasMessageContaining("无效");
    }
}
