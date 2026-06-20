package com.cayleywcs.adapter;

import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 适配器模板方法（需求 5 · Template Method）：固化连接生命周期与 readAll 的 JSON 组装，
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
            try {
                Object raw = read(point);
                snapshot.put(point.getField_name(), DataCodec.decode(point.getData_type(), raw));
            } catch (Exception ex) {
                log.debug("[{}] read point {} failed: {}", protocolType(), point.getField_name(), ex.getMessage());
            }
        }
        return snapshot;
    }

    protected void markDisconnected() {
        connected = false;
    }

    protected abstract void doConnect(AdapterContext ctx) throws Exception;

    protected abstract void doDisconnect() throws Exception;
}
