package com.cayleywcs.adapter.plc4x;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.PointTypeRegistry;
import com.cayleywcs.adapter.ProtocolException;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.plc4x.java.DefaultPlcDriverManager;
import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcConnectionManager;
import org.apache.plc4x.java.api.messages.PlcReadRequest;
import org.apache.plc4x.java.api.messages.PlcReadResponse;
import org.apache.plc4x.java.api.messages.PlcWriteRequest;
import org.apache.plc4x.java.api.messages.PlcWriteResponse;
import org.apache.plc4x.java.api.types.PlcResponseCode;

/**
 * 基于 Apache PLC4X 的统一适配器，覆盖 OPC UA / Modbus TCP / S7。
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
            // PlcConnectionManager.getConnection already connects. A second connect recreates the S7 channel.
            if (!connection.isConnected()) { throw new IllegalStateException("PLC4X 连接未完成"); }
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
        requireConnected();
        // PLC4X 连接非并发安全：worker 轮询与手动读写共用同一通道，须串行化，否则 OPC UA 通道序号错乱。
        synchronized (channelLock) {
            try {
                ModbusRegisterCodec registerCodec = "modbus_tcp".equals(protocolType) && ModbusRegisterCodec.configured(point)
                        ? new ModbusRegisterCodec(point) : null;
                PlcReadRequest request = connection.readRequestBuilder()
                        .addTagAddress(point.getField_name(), registerCodec == null ? nativeAddress(point) : registerCodec.address(point))
                        .build();
                PlcReadResponse response = request.execute().get(timeoutMs, TimeUnit.MILLISECONDS);
                checkResponse(point, response.getResponseCode(point.getField_name()));
                Object raw = response.getNumberOfValues(point.getField_name()) > 1
                        ? response.getAllObjects(point.getField_name()) : response.getObject(point.getField_name());
                if (registerCodec != null) { raw = registerCodec.decode(raw); }
                return decodeResponse(point, raw);
            } catch (Exception ex) {
                throw ProtocolException.io(point.getField_name(), ex);
            }
        }
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        requireConnected();
        Object encoded = encodeValue(point, value);
        synchronized (channelLock) {
            try {
                ModbusRegisterCodec registerCodec = "modbus_tcp".equals(protocolType) && ModbusRegisterCodec.configured(point)
                        ? new ModbusRegisterCodec(point) : null;
                Object[] values = registerCodec != null ? registerCodec.encode(encoded)
                        : encoded instanceof List<?> list ? list.toArray() : new Object[]{encoded};
                PlcWriteRequest request = connection.writeRequestBuilder()
                        .addTagAddress(point.getField_name(), registerCodec == null ? nativeAddress(point) : registerCodec.address(point), values)
                        .build();
                PlcWriteResponse response = request.execute().get(timeoutMs, TimeUnit.MILLISECONDS);
                checkResponse(point, response.getResponseCode(point.getField_name()));
            } catch (Exception ex) {
                throw ProtocolException.io(point.getField_name(), ex);
            }
        }
    }

    private void checkResponse(ProtocolPointEntity point, PlcResponseCode code) {
        if (code != PlcResponseCode.OK) {
            throw new IllegalStateException("设备点位返回失败：" + point.getField_name() + "，状态=" + code);
        }
    }

    private String nativeAddress(ProtocolPointEntity point) {
        try {return doNativeAddress(point);}
        catch(IllegalArgumentException ex) {
            throw new ProtocolException(4224,"原生地址不能编码："+point.getField_name()+"，"+ex.getMessage(),ex);
        }
    }

    private String doNativeAddress(ProtocolPointEntity point) {
        String address = point.getAddress();
        // Type in the native address must agree with the maintained point definition.
        String type = point.getData_type().trim().toUpperCase(Locale.ROOT);
        if (type.matches("ARRAY\\[[^]]*,[^]]*].*")) {
            throw new IllegalArgumentException("当前 PLC4X 适配器仅支持明确的一维点位数组");
        }
        Matcher array = Pattern.compile("ARRAY\\[[^]]+]\\s+OF\\s+(.+)").matcher(type);
        if (array.matches()) { type = array.group(1).trim(); }
        type = plcType(type);
        if (!"opcua".equals(protocolType)) {
            Matcher explicit = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)(?:\\[([0-9]+)])?$").matcher(address);
            if (explicit.find()) {
                String actual = explicit.group(1);
                if (explicit.group(2) != null) { actual = "ARRAY["+explicit.group(2)+"] OF "+actual; }
                if (!PointTypeRegistry.canonical(protocolType, point.getData_type())
                        .equals(PointTypeRegistry.canonical(protocolType, actual))) {
                    throw new IllegalArgumentException("原生地址类型与维护的点位类型不一致："+point.getField_name());
                }
                return address.substring(0,explicit.start())+":"+plcType(explicit.group(1))
                        +(explicit.group(2)==null?"":"["+explicit.group(2)+"]");
            }
            if ("s7".equals(protocolType)) { throw new IllegalArgumentException("S7 地址必须显式声明原生类型"); }
            Matcher count = Pattern.compile("ARRAY\\[([0-9]+)]\\s+OF\\s+.+").matcher(point.getData_type().trim().toUpperCase(Locale.ROOT));
            return address+":"+type+(count.matches() ? "["+count.group(1)+"]" : "");
        }
        // OPC UA unsigned types cannot be inferred from Integer/Long. Supply the native tag type.
        int separators = address.length() - address.replace(";", "").length();
        if (separators >= 2) {
            String actual = address.substring(address.lastIndexOf(';')+1);
            if (!PointTypeRegistry.canonical(protocolType, type).equals(PointTypeRegistry.canonical(protocolType, actual))) {
                throw new IllegalArgumentException("OPC UA 地址类型与维护的点位类型不一致："+point.getField_name());
            }
            return address.substring(0,address.lastIndexOf(';')+1)+plcType(actual);
        }
        return address + ";" + type;
    }

    private String plcType(String type) {
        if ("WSTRING".equals(type)) {return "WSTRING";}
        String canonical=PointTypeRegistry.canonical(protocolType,type);
        if (canonical.startsWith("ARRAY<")) {canonical=canonical.substring(6,canonical.length()-1);}
        return switch(canonical) {
            case "BOOL" -> "BOOL"; case "STRING" -> "STRING";
            case "INT8" -> "SINT"; case "UINT8" -> "USINT";
            case "INT16" -> "INT"; case "UINT16" -> "UINT";
            case "INT32" -> "DINT"; case "UINT32" -> "UDINT";
            case "INT64" -> "LINT"; case "UINT64" -> "ULINT";
            case "FLOAT32" -> "REAL"; case "FLOAT64" -> "LREAL";
            default -> throw new IllegalArgumentException("PLC4X 不支持该原生点位类型："+type);
        };
    }
}
