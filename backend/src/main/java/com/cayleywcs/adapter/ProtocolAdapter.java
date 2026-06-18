package com.cayleywcs.adapter;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * 协议适配器统一 SPI（需求 5）。到连接阶段，所有协议（OPC UA/Modbus/S7/TCP/MQTT/HTTP）都用这套接口处理连接与数据。
 */
public interface ProtocolAdapter {

    /** 协议类型字典码（opcua/modbus_tcp/s7/tcp/mqtt/http/loopback）。 */
    String protocolType();

    /** 建立连接（含协议握手）；失败抛异常，由连接治理处理超时/回收。 */
    void connect(AdapterContext ctx) throws Exception;

    void disconnect();

    boolean isConnected();

    /** 心跳/保活，存活返回 true。 */
    boolean heartbeat();

    /** 读取一组点位，统一返回 JSON（field_name -> value）。 */
    ObjectNode readAll(List<ProtocolPointEntity> points);

    Object read(ProtocolPointEntity point);

    void write(ProtocolPointEntity point, Object value);
}
