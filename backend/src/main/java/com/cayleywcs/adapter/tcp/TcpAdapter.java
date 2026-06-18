package com.cayleywcs.adapter.tcp;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 原始 TCP 适配器（骨架）。建连/保活完整；read 因原始帧格式因设备而异，默认留空，write 直接发送字节。
 * conn_params：host / port / connectTimeoutMs。实际帧解析按设备协议在子类或 codec 中扩展。
 */
public class TcpAdapter extends AbstractProtocolAdapter {
    private volatile Socket socket;

    @Override
    public String protocolType() {
        return "tcp";
    }

    @Override
    protected void doConnect(AdapterContext ctx) throws Exception {
        String host = ctx.connParam("host", "127.0.0.1");
        int port = (int) ctx.connParamLong("port", 9000);
        int timeout = (int) ctx.connParamLong("connectTimeoutMs", 5000);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeout);
        this.socket = s;
    }

    @Override
    protected void doDisconnect() throws Exception {
        Socket s = socket;
        socket = null;
        if (s != null) {
            s.close();
        }
    }

    @Override
    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    @Override
    public boolean heartbeat() {
        return isConnected();
    }

    @Override
    public Object read(ProtocolPointEntity point) {
        // 原始 TCP 无统一寻址语义；具体帧解析按设备协议扩展。
        return null;
    }

    @Override
    public void write(ProtocolPointEntity point, Object value) {
        try {
            socket.getOutputStream().write(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (Exception ex) {
            throw new RuntimeException("TCP write failed", ex);
        }
    }
}
