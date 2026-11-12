package com.cayleywcs.agv;

import com.cayleywcs.adapter.*;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.adapter.seer.*;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.*;
import com.cayleywcs.protocol.impl.ProtocolServiceImpl;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SeerPointCatalogTest {
    @Test void typedPointsReadThreePacketsOnceAndPreserveMissingValues() throws Exception {
        try(var sim=new ReadOnlyProtocolSimulator()) {
            var endpoints=new DeviceEndpoints(new MockEnvironment().withProperty("cayleywcs.endpoints.vehicle.host","127.0.0.1")
                    .withProperty("cayleywcs.endpoints.vehicle.port",Integer.toString(sim.seer.getLocalPort())));
            var app=new ApplicationEntity();app.setTenant_id(1L);app.setConn_params(Map.of("endpointRef","vehicle"));
            var protocol=new ProtocolEntity();protocol.setProtocol_type("tcp");
            var points=SeerPointCatalog.points(1L);
            ProtocolServiceImpl.validatePublication("tcp",points);
            var adapter=new SeerTcpAdapter(endpoints);
            try {
                adapter.connect(new AdapterContext(app,protocol,points));
                var state=adapter.readAll(points);
                assertThat(state).hasSize(43).containsEntry("loc_x",1.25).containsEntry("loc_current_station","测试站").containsEntry("nav_task_status",2);
                assertThat(state).containsKey("loc_confidence");assertThat(state.get("loc_confidence")).isNull();
                assertThat(state.get("seer_location").toString()).contains("receivedAt");
                assertThat(sim.seerApis).containsExactly(1004,1005,1020);
                assertThatThrownBy(()->adapter.write(points.getFirst(),1)).hasMessageContaining("只读");
                assertThat(sim.seerApis).hasSize(3);
                points.getFirst().setAddress("3051:/x");
                assertThatThrownBy(()->ProtocolServiceImpl.validatePublication("tcp",points)).hasMessageContaining("映射");
            } finally {adapter.disconnect();}
        }
    }
}
