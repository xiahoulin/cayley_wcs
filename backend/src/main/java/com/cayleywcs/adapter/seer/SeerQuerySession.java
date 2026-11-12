package com.cayleywcs.adapter.seer;

import com.cayleywcs.adapter.readonly.DeadlineSocket;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** No command API. Only the three confirmed state queries are admitted. */
public final class SeerQuerySession implements AutoCloseable {
    public static final Set<Integer> APIS = Set.of(1004, 1005, 1020);
    public static final int MAX_BODY = 65536;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final DeadlineSocket channel;
    private final boolean allowMissingRetCode;
    private int sequence;
    public SeerQuerySession(DeviceEndpoints.Endpoint endpoint) {
        channel = new DeadlineSocket(endpoint); allowMissingRetCode = endpoint.allowMissingRetCode();
    }
    public void connect() throws IOException { channel.connect(); }
    public boolean connected() { return channel.connected(); }
    public synchronized Map<String,Object> query(int api) throws IOException {
        if (!APIS.contains(api)) throw new IllegalArgumentException("只读会话拒绝 API: " + api);
        if (sequence == 65535) { channel.close(); sequence = 0; }
        int requestId = ++sequence;
        return channel.exchange(frame(requestId, api, new byte[0]), reader -> {
            ByteBuffer header = ByteBuffer.wrap(reader.read(16)).order(ByteOrder.BIG_ENDIAN);
            if (Byte.toUnsignedInt(header.get()) != 0x5a || header.get() != 1)
                throw new IOException("invalid SEER header/version");
            int responseId = Short.toUnsignedInt(header.getShort());
            long length = Integer.toUnsignedLong(header.getInt());
            int responseApi = Short.toUnsignedInt(header.getShort());
            // This SRC-1100 echoes the request API in bytes 10–11; legacy replies keep all six bytes zero.
            int echoedApi=Short.toUnsignedInt(header.getShort());
            if ((echoedApi!=0 && echoedApi!=api) || header.getInt()!=0) throw new IOException("invalid SEER reserved bytes");
            if (responseId != requestId || responseApi != api + 10000 || length < 2 || length > MAX_BODY)
                throw new IOException("SEER response sequence/API/length mismatch");
            JsonNode body = JSON.readTree(reader.read((int) length));
            if (body == null || !body.isObject()) throw new IOException("SEER response must be a JSON object");
            JsonNode ret = body.get("ret_code");
            if (ret == null ? !allowMissingRetCode : !ret.isIntegralNumber() || !ret.canConvertToInt() || ret.intValue() != 0)
                throw new IOException("SEER ret_code missing or unsuccessful");
            for (String field : api == 1004 ? new String[]{"x","y","angle"} : api == 1005 ? new String[]{"vx","vy","w"} : new String[]{"task_status"}) {
                JsonNode value=body.get(field);
                if (value==null || !value.isNumber() || !Double.isFinite(value.doubleValue()))
                    throw new IOException("SEER required state field missing or invalid: " + field);
            }
            if (api==1020 && (!body.get("task_status").isIntegralNumber() || !body.get("task_status").canConvertToInt() || body.get("task_status").intValue()<0 || body.get("task_status").intValue()>6))
                throw new IOException("SEER task_status not in the confirmed status profile");
            return Map.of("api", api, "sequence",requestId,"headerMode",echoedApi==0?"zero-reserved":"echo-request-api",
                    "receivedAt", Instant.now().toString(), "payload", JSON.convertValue(body, Map.class));
        });
    }
    public static byte[] frame(int sequence, int api, byte[] body) {
        if (sequence < 0 || sequence > 65535 || api < 0 || api > 65535 || body.length > MAX_BODY)
            throw new IllegalArgumentException("SEER frame out of bounds");
        return ByteBuffer.allocate(16 + body.length).order(ByteOrder.BIG_ENDIAN)
                .put((byte) 0x5a).put((byte) 1).putShort((short) sequence).putInt(body.length)
                .putShort((short) api).put(new byte[6]).put(body).array();
    }
    @Override public void close() { channel.shutdown(); }
}
