package com.cayleywcs.adapter;

/** Entry points retained for existing internal callers; transport callers must supply protocol context. */
public final class DataCodec {
    private DataCodec() { }
    public static Object decode(String dataType, Object raw) { return decode("sim", dataType, raw); }
    public static Object encode(String dataType, Object value) { return encode("sim", dataType, value); }
    public static Object decode(String protocol, String dataType, Object raw) { return PointTypeRegistry.decode(protocol, dataType, raw); }
    public static Object encode(String protocol, String dataType, Object value) { return PointTypeRegistry.encode(protocol, dataType, value); }
}
