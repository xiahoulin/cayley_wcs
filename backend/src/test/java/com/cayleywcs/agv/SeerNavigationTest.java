package com.cayleywcs.agv;

import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.adapter.seer.*;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.impl.ProtocolServiceImpl;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SeerNavigationTest {
    @ParameterizedTest @CsvSource({"ok,accepted,true","reject,rejected,false","generic,rejected,false","missing,unknown,false","string,unknown,false","sequence,unknown,false","timeout,unknown,false"})
    void realTcpResponseIsStrictAndNeverRetried(String mode,String status,boolean success) {
        try(var sim=new SeerNavigationSimulator()) {
            sim.mode=mode;
            var result=SeerNavigationSession.send(new DeviceEndpoints.Endpoint("127.0.0.1",sim.navigation.getLocalPort(),250,false),"中文站点");
            assertThat(result.sent()).isEqualTo(success);assertThat(result.status()).isEqualTo(status);
            assertThat(sim.commands).containsExactly(Map.of("id","中文站点"));
        }
    }
    @Test void newContractPreservesStatusAndOldContractStillPublishes() {
        ProtocolServiceImpl.validatePublication("tcp",SeerPointCatalog.points(1L));
        var points=SeerPointCatalog.navigationContract(1L);ProtocolServiceImpl.validatePublication("tcp",points);
        assertThat(points).hasSize(45);
        points.getLast().setAddress("3050:/sent");
        assertThatThrownBy(()->ProtocolServiceImpl.validatePublication("tcp",points)).hasMessageContaining("映射");
        assertThatThrownBy(()->SeerNavigationSession.station(" AP0")).hasMessageContaining("站点");
        assertThatThrownBy(()->SeerNavigationSession.station("\nAP0")).hasMessageContaining("站点");
    }
    @Test void applicationIpOverridesHostWithoutChangingLegacyParameters() {
        var endpoints=new DeviceEndpoints(new MockEnvironment().withProperty("cayleywcs.endpoints.test.host","127.0.0.2")
                .withProperty("cayleywcs.endpoints.test.port","23456").withProperty("cayleywcs.endpoints.test.timeout-ms","1700")
                .withProperty("cayleywcs.endpoints.test.allow-missing-ret-code","true"));
        var app=new ApplicationEntity();app.setTenant_id(1L);app.setConn_params(Map.of("endpointRef","test","ip","127.0.0.1"));
        var status=SeerConnectionSettings.endpoint(app,endpoints,false);
        var nav=SeerConnectionSettings.endpoint(app,endpoints,true);
        assertThat(status).isEqualTo(new DeviceEndpoints.Endpoint("127.0.0.1",23456,1700,true));
        assertThat(nav).isEqualTo(new DeviceEndpoints.Endpoint("127.0.0.1",19206,1700,false));
        app.setTenant_id(2L);assertThatThrownBy(()->SeerConnectionSettings.endpoint(app,endpoints,false)).hasMessageContaining("不可访问");
        for(String ip:List.of("999.0.0.1","host.example","http://127.0.0.1","127.00.0.1","0.0.0.0","224.1.2.3"))
            assertThatThrownBy(()->SeerConnectionSettings.validate(Map.of("ip",ip))).hasMessageContaining("IPv4");
        assertThatThrownBy(()->SeerConnectionSettings.validate(Map.of("navigationPort",1.5))).hasMessageContaining("参数");
    }
}
