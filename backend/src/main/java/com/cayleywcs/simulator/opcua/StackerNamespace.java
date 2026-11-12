package com.cayleywcs.simulator.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import com.cayleywcs.simulator.OpcUaStackerSimulator;
import com.cayleywcs.simulator.StackerDeviceState;
import com.cayleywcs.adapter.PointTypeRegistry;
import java.util.List;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilters;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 命名空间：把堆垛机 DB100 字段暴露为可读写节点，节点读写直连内存 {@link StackerDeviceState}。
 * NodeId 标识符 = 符号名 WCS_Task.&lt;group&gt;.&lt;field&gt;（命名空间索引由 Milo 分配，通常为 2）。
 */
public class StackerNamespace extends ManagedNamespaceWithLifecycle {
    public static final String URI = "urn:cayleywcs:stacker";
    private static final Logger log = LoggerFactory.getLogger(StackerNamespace.class);

    private final StackerDeviceState device;
    private final SubscriptionModel subscriptionModel;

    public StackerNamespace(OpcUaServer server, StackerDeviceState device) {
        super(server, URI);
        this.device = device;
        this.subscriptionModel = new SubscriptionModel(server, this);
        getLifecycleManager().addLifecycle(subscriptionModel);
        getLifecycleManager().addStartupTask(this::createNodes);
    }

    private void createNodes() {
        NodeId folderId = newNodeId("WCS_Task");
        UaFolderNode folder = new UaFolderNode(getNodeContext(), folderId,
                newQualifiedName("WCS_Task"), LocalizedText.english("WCS_Task"));
        getNodeManager().addNode(folder);
        folder.addReference(new Reference(folder.getNodeId(), Identifiers.Organizes,
                Identifiers.ObjectsFolder.expanded(), false));

        for (OpcUaStackerSimulator.Field f : OpcUaStackerSimulator.FIELDS) {
            String sid = "WCS_Task." + f.group() + "." + f.field();
            UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                    .setNodeId(newNodeId(sid))
                    .setAccessLevel(ubyte(access(f)))
                    .setUserAccessLevel(ubyte(access(f)))
                    .setBrowseName(newQualifiedName(f.field()))
                    .setDisplayName(LocalizedText.english(f.field()))
                    .setDataType(dataType(f))
                    .setValueRank(f.nativeType().startsWith("ARRAY") ? 1 : -1)
                    .setArrayDimensions(f.nativeType().startsWith("ARRAY") ? new org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger[]{uint(60)} : null)
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .build();
            node.setValue(new DataValue(new Variant(readDevice(f))));
            node.getFilterChain().addLast(AttributeFilters.getValue(
                    ctx -> new DataValue(new Variant(readDevice(f)))));
            UaVariableNode boundNode = node;
            node.getFilterChain().addLast(AttributeFilters.setValue(
                    (ctx, value) -> {
                        // 原始 OPC UA 写入包（跳过每秒心跳 WCS_Heart）：打印节点 NodeId 与原始 DataValue
                        // （含 Variant 值+内建类型、StatusCode、时间戳），不做任何自定义解释，用于核对指令与数据是否正确。
                        if (!"WCS_Heart".equals(f.field())) {
                            log.info("[OPC UA写入] node={} dataValue={}", boundNode.getNodeId().toParseableString(), value);
                        }
                        device.set(f.field(), PointTypeRegistry.encode("opcua", f.nativeType(), value.getValue().getValue()));
                    }));
            getNodeManager().addNode(node);
            folder.addOrganizes(node);
        }
    }

    private Object readDevice(OpcUaStackerSimulator.Field f) {
        Object v = device.get(f.field());
        if (f.nativeType().startsWith("ARRAY")) {
            List<Object> values = PointTypeRegistry.sequence(v);
            if (values.size() != 60) { throw new IllegalStateException("仿真故障数组必须为60元素"); }
            return values.stream().map(value -> ((Number) value).shortValue()).toArray(Short[]::new);
        }
        Number n = v instanceof Number num ? num : 0;
        return switch (f.nativeType()) {
            case "REAL" -> n.floatValue();
            case "UINT" -> ushort(n.intValue());
            default -> n.shortValue();
        };
    }

    private static int access(OpcUaStackerSimulator.Field field) {
        // 请求节点可写入并读回确认，响应节点保持真实服务端只读，拒写由 OPC UA 服务端返回。
        return "request".equals(field.direction()) ? 3 : 1;
    }

    private static NodeId dataType(OpcUaStackerSimulator.Field field) {
        return switch (field.nativeType()) {
            case "REAL" -> Identifiers.Float;
            case "UINT" -> Identifiers.UInt16;
            default -> Identifiers.Int16;
        };
    }

    @Override
    public void onDataItemsCreated(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsCreated(dataItems);
    }

    @Override
    public void onDataItemsModified(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsModified(dataItems);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> dataItems) {
        subscriptionModel.onDataItemsDeleted(dataItems);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
        subscriptionModel.onMonitoringModeChanged(monitoredItems);
    }
}
