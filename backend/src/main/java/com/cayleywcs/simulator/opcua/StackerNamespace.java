package com.cayleywcs.simulator.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte;

import com.cayleywcs.simulator.OpcUaStackerSimulator;
import com.cayleywcs.simulator.StackerDeviceState;
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

/**
 * OPC UA 命名空间：把堆垛机 DB100 字段暴露为可读写节点，节点读写直连内存 {@link StackerDeviceState}。
 * NodeId 标识符 = 符号名 WCS_Task.&lt;group&gt;.&lt;field&gt;（命名空间索引由 Milo 分配，通常为 2）。
 */
public class StackerNamespace extends ManagedNamespaceWithLifecycle {
    public static final String URI = "urn:cayleywcs:stacker";

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
                    .setAccessLevel(ubyte(3))       // CurrentRead | CurrentWrite
                    .setUserAccessLevel(ubyte(3))
                    .setBrowseName(newQualifiedName(f.field()))
                    .setDisplayName(LocalizedText.english(f.field()))
                    .setDataType(f.real() ? Identifiers.Double : Identifiers.Int64)
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .build();
            node.setValue(new DataValue(new Variant(f.real() ? (double) 0 : (long) 0)));
            node.getFilterChain().addLast(AttributeFilters.getValue(
                    ctx -> new DataValue(new Variant(readDevice(f)))));
            node.getFilterChain().addLast(AttributeFilters.setValue(
                    (ctx, value) -> device.set(f.field(), value.getValue().getValue())));
            getNodeManager().addNode(node);
            folder.addOrganizes(node);
        }
    }

    private Object readDevice(OpcUaStackerSimulator.Field f) {
        Object v = device.get(f.field());
        Number n = v instanceof Number num ? num : 0;
        return f.real() ? (Object) n.doubleValue() : (Object) n.longValue();
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
