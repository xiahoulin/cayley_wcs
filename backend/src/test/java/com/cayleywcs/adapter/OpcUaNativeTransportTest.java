package com.cayleywcs.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.context;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.point;
import com.cayleywcs.adapter.plc4x.Plc4xAdapter;
import com.cayleywcs.simulator.OpcUaStackerSimulator;
import com.cayleywcs.simulator.StackerSimulatorRegistry;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;

/** Actual PLC4X TCP connection to typed Milo nodes, including device-side access/type rejections. */
class OpcUaNativeTransportTest {
    public static void main(String[] args) throws Exception {
        new OpcUaNativeTransportTest().nativeTypesServerAccessFaultArrayAndStackerHandshake();
        new OpcUaNativeTransportTest().additionalDeclaredNativeTypesUseActualVariants();
        System.out.println("OPCUA_REAL_NETWORK_PASS Int16/UInt16/Float/Int16[60]/permission/type/stacker_handshake");
        org.eclipse.milo.opcua.stack.core.Stack.releaseSharedResources();
    }
    @Test void additionalDeclaredNativeTypesUseActualVariants() throws Exception {
        int port; try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var simulator=new OpcUaStackerSimulator(new StackerSimulatorRegistry(),port,"127.0.0.1","/cayleywcs/stacker");
        simulator.start();
        var server=(OpcUaServer)ReflectionTestUtils.getField(simulator,"server");
        var namespace=new TypedNamespace(server); namespace.startup();
        var adapter=new Plc4xAdapter("opcua","opcua:tcp://127.0.0.1:"+port+"/cayleywcs/stacker?discovery=false",5000);
        adapter.connect(context("opcua",List.of(),Map.of()));
        try {
            Map<String,Object> samples=Map.of("Int32",-2147483648,"Int64",Long.MAX_VALUE,"Float64",12345.125,
                    "Boolean",true,"String","礁盘WCS ✓","BoolArray",List.of(true,false,true),
                    "UInt32",4294967295L);
            Map<String,String> types=Map.of("Int32","INT32","Int64","INTEGER","Float64","DOUBLE",
                    "Boolean","BOOLEAN","String","TEXT","BoolArray","ARRAY[3] OF BOOL",
                    "UInt32","UINT32","UInt64","UINT64");
            for(var sample:samples.entrySet()) {
                var p=point(sample.getKey(),"ns="+namespace.getNamespaceIndex()+";s="+sample.getKey(),types.get(sample.getKey()),"request");
                adapter.write(p,sample.getValue());
                assertThat(adapter.read(p)).isEqualTo(DataCodec.decode("opcua",p.getData_type(),sample.getValue()));
            }
            // The underlying PLC4X UInt64 writer truncates the high half. Reject this capability before I/O.
            var unsupported=point("UInt64","ns="+namespace.getNamespaceIndex()+";s=UInt64","UINT64","request");
            assertThatThrownBy(()->adapter.write(unsupported,new java.math.BigInteger("18446744073709551615")))
                    .isInstanceOfSatisfying(com.cayleywcs.common.exception.WcsException.class,error->assertThat(error.getCode()).isEqualTo(4224));
            assertThat(namespace.value("UInt64").toString()).isEqualTo("0");
            var unsupportedArray=point("UInt64","ns="+namespace.getNamespaceIndex()+";s=UInt64","ARRAY[2] OF ULINT","request");
            assertThatThrownBy(()->adapter.write(unsupportedArray,List.of(java.math.BigInteger.ZERO,java.math.BigInteger.ONE)))
                    .isInstanceOfSatisfying(com.cayleywcs.common.exception.WcsException.class,error->assertThat(error.getCode()).isEqualTo(4224));
            assertThat(PointTypeRegistry.options("opcua")).doesNotContain("UINT64");
            assertThat(PointTypeRegistry.compatible("http","UINT64","opcua","ULINT")).isFalse();
        } finally {adapter.disconnect();namespace.shutdown();simulator.stop();}
    }

    private static final class TypedNamespace extends ManagedNamespaceWithLifecycle {
        TypedNamespace(OpcUaServer server) {
            super(server,"urn:cayleywcs:test:native-types");
            getLifecycleManager().addStartupTask(()->{
                add("Int32",Identifiers.Int32,0,-1); add("Int64",Identifiers.Int64,0L,-1);
                add("Float64",Identifiers.Double,0.0,-1); add("Boolean",Identifiers.Boolean,false,-1);
                add("String",Identifiers.String,"",-1); add("BoolArray",Identifiers.Boolean,new Boolean[]{false,false,false},1);
                add("UInt32",Identifiers.UInt32,org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint(0),-1);
                add("UInt64",Identifiers.UInt64,org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ulong(0),-1);
            });
        }
        void add(String name,NodeId type,Object initial,int rank) {
            var node=new UaVariableNode.UaVariableNodeBuilder(getNodeContext()).setNodeId(newNodeId(name))
                    .setBrowseName(newQualifiedName(name)).setDisplayName(LocalizedText.english(name))
                    .setDataType(type).setValueRank(rank).setAccessLevel(ubyte(3)).setUserAccessLevel(ubyte(3))
                    .setTypeDefinition(Identifiers.BaseDataVariableType).build();
            node.setValue(new DataValue(new Variant(initial))); getNodeManager().addNode(node);
        }
        Object value(String name) {return ((UaVariableNode)getNodeManager().getNode(newNodeId(name)).orElseThrow()).getValue().getValue().getValue();}
        @Override public void onDataItemsCreated(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> items) { }
        @Override public void onDataItemsModified(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> items) { }
        @Override public void onDataItemsDeleted(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> items) { }
        @Override public void onMonitoringModeChanged(List<org.eclipse.milo.opcua.sdk.server.api.MonitoredItem> items) { }
    }

    @Test void nativeTypesServerAccessFaultArrayAndStackerHandshake() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var registry = new StackerSimulatorRegistry();
        var simulator = new OpcUaStackerSimulator(registry, port, "127.0.0.1", "/cayleywcs/stacker");
        simulator.start();
        var adapter = new Plc4xAdapter("opcua", "opcua:tcp://127.0.0.1:"+port+"/cayleywcs/stacker?discovery=false", 5000);
        var number = point("cmd_TaskNum", address("From_WCS", "cmd_TaskNum"), "UINT", "request");
        number.setPoint_scope("internal");
        var faults = point("status_ErrorCode", address("To_WCS", "status_ErrorCode"), "ARRAY[60] OF INT", "response");
        adapter.connect(context("opcua", List.of(number, faults), Map.of()));
        try {
            adapter.write(number, 65534); assertThat(adapter.read(number)).isEqualTo(65534L);
            assertThatThrownBy(() -> adapter.write(number, 65536)).isInstanceOf(RuntimeException.class);
            var mode = point("status_Mode", address("To_WCS", "status_Mode"), "INT", "response");
            assertThatThrownBy(() -> adapter.write(mode, 7)).isInstanceOf(RuntimeException.class);
            assertThat(adapter.read(mode)).isEqualTo(2L);
            // Bypass local template type solely to exercise the actual server's Bad_TypeMismatch response.
            var wrong = point("cmd_TaskNum", address("From_WCS", "cmd_TaskNum"), "REAL", "request");
            assertThatThrownBy(() -> adapter.write(wrong, 123.5)).isInstanceOf(RuntimeException.class);
            write(adapter, "cmd_TaskType", 1); write(adapter, "cmd_TakeCoor_Row", 1);
            write(adapter, "cmd_TakeCoor_Column", 3); write(adapter, "cmd_TakeCoor_Floor", 4);
            write(adapter, "cmd_PutCoor_Row", 1); write(adapter, "cmd_PutCoor_Column", 5);
            write(adapter, "cmd_PutCoor_Floor", 6); write(adapter, "cmd_ConfirmTask", 1);
            var device = registry.getOrCreate(OpcUaStackerSimulator.REGISTRY_APP_ID);
            assertThat(read(adapter, "status_Task", "INT")).isEqualTo(1L);
            assertThat(read(adapter, "status_TaskNum", "UINT")).isEqualTo(65534L);
            device.tick();
            assertThat(read(adapter, "status_Speed_Walk", "REAL")).isEqualTo(1500.0);
            device.tick(); assertThat(read(adapter, "status_Task", "INT")).isEqualTo(2L);
            assertThat(read(adapter, "status_CurrentColumnNum", "INT")).isEqualTo(5L);
            write(adapter, "cmd_ConfirmTask", 2);
            assertThat(read(adapter, "status_Task", "INT")).isEqualTo(0L);
            device.injectFault(18);
            assertThat((List<?>)adapter.read(faults)).hasSize(60);
            assertThat(((List<?>)adapter.read(faults)).getFirst()).isEqualTo(18L);
            write(adapter, "cmd_ResetFault", 1);
            assertThat(((List<?>)adapter.read(faults)).getFirst()).isEqualTo(0L);
            // 驱动可显式读回请求参数；响应节点是否可写由真实服务端控制。
            assertThat(adapter.read(point("cmd_TaskType", address("From_WCS", "cmd_TaskType"), "INT", "request"))).isEqualTo(1L);
        } finally { adapter.disconnect(); simulator.stop(); }
    }
    private static String address(String group, String field) { return "ns=2;s=WCS_Task."+group+"."+field; }
    private static void write(Plc4xAdapter adapter, String field, Object value) {
        adapter.write(point(field, address("From_WCS", field), "INT", "request"), value);
    }
    private static Object read(Plc4xAdapter adapter, String field, String type) {
        return adapter.read(point(field, address("To_WCS", field), type, "response"));
    }
}
