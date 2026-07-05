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
                    (ctx, value) -> {
                        Object written = value.getValue().getValue();
                        // 仿真观测：OPC UA 服务端收到 WCS 写入的指令（仅命令区 cmd_*，跳过每秒心跳），
                        // 打到 stdout 便于在 docker logs / Docker Desktop 实时看到指令逐条进来。
                        if (f.field().startsWith("cmd_")) {
                            log.info("[OPC UA仿真] 收到指令写入 {} = {}", f.field(), written);
                        }
                        device.set(f.field(), written);
                        // 确认执行(cmd_ConfirmTask=1)时，命令区参数已全部就位——组装成一条完整指令数据打印。
                        if ("cmd_ConfirmTask".equals(f.field()) && toLong(written) == 1L) {
                            logFullCommand();
                        }
                    }));
            getNodeManager().addNode(node);
            folder.addOrganizes(node);
        }
    }

    private Object readDevice(OpcUaStackerSimulator.Field f) {
        Object v = device.get(f.field());
        Number n = v instanceof Number num ? num : 0;
        return f.real() ? (Object) n.doubleValue() : (Object) n.longValue();
    }

    /** 组装并打印一条完整指令数据（确认执行时命令区参数已就位）。 */
    private void logFullCommand() {
        long type = toLong(device.get("cmd_TaskType"));
        log.info("[OPC UA仿真] ★收到完整指令★ 任务号={} 类型={}({}) 取货[排{} 列{} 层{}] 放货[排{} 列{} 层{}] 货口={}",
                toLong(device.get("cmd_TaskNum")), type, taskTypeName(type),
                toLong(device.get("cmd_TakeCoor_Row")), toLong(device.get("cmd_TakeCoor_Column")),
                toLong(device.get("cmd_TakeCoor_Floor")),
                toLong(device.get("cmd_PutCoor_Row")), toLong(device.get("cmd_PutCoor_Column")),
                toLong(device.get("cmd_PutCoor_Floor")),
                toLong(device.get("cmd_PortNum")));
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String taskTypeName(long type) {
        return switch ((int) type) {
            case 1 -> "入库";
            case 2 -> "出库";
            case 3 -> "移库";
            case 4 -> "移动";
            case 5 -> "急停";
            case 6 -> "解警";
            case 7 -> "充电";
            default -> "未知";
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
