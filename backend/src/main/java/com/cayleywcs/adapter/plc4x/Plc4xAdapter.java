package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.DataCodec;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.concurrent.TimeUnit;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcConnectionManager;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;

/**
 * 基于 Apache PLC4X 的统一适配器，覆盖 OPC UA / Modbus TCP / S7（需求 5）。
 * point.address 直接作为 PLC4X tag 地址（OPC UA 为 NodeId；S7 为 %DB100.DBW0:INT；Modbus 为 holding-register 等）。
 */
public class Plc4xAdapter extends AbstractProtocolAdapter {
    private final String protocolType;
    private final String connectionString;
    private final long timeoutMs;
    private final PlcConnectionManager connectionManager = new DefaultPlcDriverManager();
    private final Object channelLock = new Object();
    private volatile PlcConnection connection;

    public Plc4xAdapter(String protocolType, String connectionString, long timeoutMs) {
        this.protocolType = protocolType;
        this.connectionString = connectionString;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String protocolType() {
        return protocolType;
    }

    @Override
    protected void doConnect(AdapterContext ctx) throws Exception {
        synchronized (channelLock) {
            connection = connectionManager.getConnection(connectionString);
            connection.connect();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        synchronized (channelLock) {
            PlcConnection conn = connection;
            connection = null;
            if (conn != null) {
                conn.close();
            }
        }
    }

    @Override
    public boolean isConnected() {
        PlcConnection conn = connection;
        return conn != null && conn.isConnected();
    }

    @Override
    public boolean heartbeat() {
        return isConnected();
    }

    @Override
    public Object read(ProtocolPointEntity point) {
        // PLC4X 连接非并发安全：worker 轮询与手动读写共用同一通道，须串行化，否则 OPC UA 通道序号错乱。
        synchronized (channelLock) {
            try {
                PlcReadRequest request = connection.readRequestBuilder()
                        .addTagAddress(point.getField_name(), point.getAddress())
                        .build();
                PlcReadResponse response = request.execute().get(timeoutMs, TimeUnit.MILLISECONDS);
                return response.getObject(point.getField_name());
            } catch (Exception ex) {
                throw new RuntimeException("PLC4X read failed: " + point.getField_name() + " -> " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        synchronized (channelLock) {
            try {
                Object encoded = DataCodec.encode(point.getData_type(), value);
                PlcWriteRequest request = connection.writeRequestBuilder()
                        .addTagAddress(point.getField_name(), point.getAddress(), encoded)
                        .build();
                request.execute().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                throw new RuntimeException("PLC4X write failed: " + point.getField_name() + " -> " + ex.getMessage(), ex);
            }
        }
    }
}
