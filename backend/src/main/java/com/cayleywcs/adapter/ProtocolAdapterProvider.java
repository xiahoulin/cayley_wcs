package com.cayleywcs.adapter;

/**
 * 适配器提供者（Strategy/Factory 注册项）。每种协议一个 Provider Bean，由 {@link ProtocolAdapterFactory} 按类型选择。
 */
public interface ProtocolAdapterProvider {

    /** 支持的协议类型字典码。 */
    String protocolType();

    /** 为某次连接创建一个独立的适配器实例（连接级隔离）。 */
    ProtocolAdapter create(AdapterContext ctx);
}
