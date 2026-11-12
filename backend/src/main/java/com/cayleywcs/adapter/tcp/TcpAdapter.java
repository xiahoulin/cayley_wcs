package com.cayleywcs.adapter.tcp;

import com.cayleywcs.adapter.AbstractProtocolAdapter;
import com.cayleywcs.adapter.AdapterContext;
import com.cayleywcs.adapter.ProtocolException;
import com.cayleywcs.adapter.message.PointMessage;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** v1 默认帧：4 字节网络字节序长度 + UTF-8 PointMessage JSON；串行关联读写。 */
public class TcpAdapter extends AbstractProtocolAdapter {
    private volatile Socket socket;
    private final Object ioLock = new Object();
    private int maxFrameBytes;
    private int requestTimeoutMs;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread=new Thread(task,"wcs-tcp-point-timeout"); thread.setDaemon(true); return thread;
    });

    @Override public String protocolType() { return "tcp"; }

    @Override protected void doConnect(AdapterContext ctx) throws Exception {
        if (!"point-json-v1".equals(ctx.connParam("frameFormat", "point-json-v1"))) {
            throw new IllegalArgumentException("TCP 帧格式不受支持，必须配置对应 codec");
        }
        maxFrameBytes = Math.toIntExact(ctx.connParamLong("maxFrameBytes", 1048576));
        int timeout = Math.toIntExact(ctx.connParamLong("requestTimeoutMs", 5000));
        requestTimeoutMs=timeout;
        if (maxFrameBytes <= 0 || maxFrameBytes > 8388608 || timeout <= 0) {
            throw new IllegalArgumentException("TCP 超时或帧长度上限无效");
        }
        Socket next = new Socket();
        try {
            next.connect(new InetSocketAddress(ctx.connParam("host", "127.0.0.1"),
                    Math.toIntExact(ctx.connParamLong("port", 9000))),
                    Math.toIntExact(ctx.connParamLong("connectTimeoutMs", 5000)));
            next.setSoTimeout(timeout);
            next.setTcpNoDelay(true);
            socket = next;
        } catch (Exception ex) { next.close(); throw ex; }
    }

    @Override protected void doDisconnect() throws Exception {
        Socket previous = socket;
        socket = null;
        if (previous != null) { previous.close(); }
    }
    @Override public boolean isConnected() {
        Socket active = socket;
        return active != null && active.isConnected() && !active.isClosed();
    }
    @Override public boolean heartbeat() { return isConnected(); }

    @Override public Object read(ProtocolPointEntity point) {
        requireConnected();
        return decodeResponse(point, exchange(point,
                PointMessage.request("read", point.getField_name(), null)));
    }

    @Override public void write(ProtocolPointEntity point, Object value) {
        requireConnected();
        Object encoded=encodeValue(point, value);
        Object returned = exchange(point, PointMessage.request("write", point.getField_name(), encoded));
        verifyWriteEcho(point, encoded, returned);
    }

    private Object exchange(ProtocolPointEntity point, PointMessage message) {
        synchronized (ioLock) {
            Socket active = socket;
            var deadline = TIMEOUTS.schedule(() -> {
                try { if (active!=null) { active.close(); } } catch (java.io.IOException ignored) { }
            }, requestTimeoutMs, TimeUnit.MILLISECONDS);
            try {
                if (active == null || active.isClosed()) { throw new IllegalStateException("TCP 已断开"); }
                byte[] request = message.bytes();
                if (request.length > maxFrameBytes) { throw new IllegalArgumentException("TCP 请求超过帧长度上限"); }
                var output = new DataOutputStream(active.getOutputStream());
                output.writeInt(request.length);
                output.write(request);
                output.flush();
                var input = new DataInputStream(active.getInputStream());
                int length = input.readInt();
                if (length <= 0 || length > maxFrameBytes) { throw new ProtocolException(4225,"TCP 响应帧长度无效"); }
                byte[] response = new byte[length];
                input.readFully(response);
                return PointMessage.parse(response).responseValue(message, point.getField_name());
            } catch (Exception ex) {
                disconnect();
                throw ProtocolException.io(point.getField_name(), ex);
            } finally { deadline.cancel(false); }
        }
    }
}
