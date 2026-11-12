package com.cayleywcs.adapter;

/** Application-level packet/subscription strategy, independent of the transport protocol registry. */
public interface ApplicationAdapterProvider {
    String adapterType();
    ProtocolAdapter create(AdapterContext context);
}
