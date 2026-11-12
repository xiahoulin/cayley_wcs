package com.cayleywcs.adapter;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 适配器模板方法：固化连接生命周期与 readAll 的 JSON 组装，
 * 子类只实现 doConnect/doDisconnect/read/write/heartbeat 等协议细节。
 */
public abstract class AbstractProtocolAdapter implements ProtocolAdapter {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    private volatile boolean connected = false;
    protected AdapterContext context;

    @Override
    public final void connect(AdapterContext ctx) throws Exception {
        this.context = ctx;
        log.info("[{}] connecting protocol={} app={}", protocolType(), ctx.protocol().getProtocol_code(),
                ctx.application().getApp_code());
        doConnect(ctx);
        connected = true;
        log.info("[{}] connected app={}", protocolType(), ctx.application().getApp_code());
    }

    @Override
    public final void disconnect() {
        try {
            doDisconnect();
        } catch (Exception ex) {
            log.warn("[{}] disconnect error: {}", protocolType(), ex.getMessage());
        } finally {
            connected = false;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public Map<String, Object> readAll(List<ProtocolPointEntity> points) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (ProtocolPointEntity point : points) {
            if (!"response".equals(point.getDirection())) {
                continue;
            }
            Object raw = read(point);
            Object value = decodeResponse(point, raw);
            if (value == null || snapshot.putIfAbsent(point.getField_name(), value) != null) {
                throw new ProtocolException(4225,"点位采集值缺失或字段重复：" + point.getField_name());
            }
        }
        return snapshot;
    }

    protected void requireConnected() {
        if (!isConnected()) { throw new ProtocolException(4222,"协议连接尚未建立"); }
    }

    protected void verifyWriteEcho(ProtocolPointEntity point, Object expected, Object returned) {
        Object normalExpected = decodeResponse(point, expected);
        Object normalReturned = decodeResponse(point, returned);
        if (!java.util.Objects.equals(normalExpected, normalReturned)) {
            throw new ProtocolException(4225,"设备写入回执与请求点位值不同："+point.getField_name());
        }
    }

    protected Object encodeValue(ProtocolPointEntity point,Object value) {
        try { return DataCodec.encode(protocolType(),point.getData_type(),value); }
        catch (IllegalArgumentException ex) { throw new ProtocolException(4224,"点位值不可编码："+point.getField_name()+"，"+ex.getMessage(),ex); }
    }
    protected Object decodeResponse(ProtocolPointEntity point,Object value) {
        try { return DataCodec.decode(protocolType(),point.getData_type(),value); }
        catch (IllegalArgumentException ex) { throw new ProtocolException(4225,"响应点位值不匹配："+point.getField_name()+"，"+ex.getMessage(),ex); }
    }

    protected void markDisconnected() {
        connected = false;
    }

    protected abstract void doConnect(AdapterContext ctx) throws Exception;

    protected abstract void doDisconnect() throws Exception;
}
