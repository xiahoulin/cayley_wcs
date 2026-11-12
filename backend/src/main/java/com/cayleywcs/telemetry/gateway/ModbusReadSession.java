package com.cayleywcs.telemetry.gateway;

import com.cayleywcs.adapter.readonly.DeadlineSocket;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal FC03-only transport for the DR164 bus. No retries, probes, or register writes. */
public final class ModbusReadSession implements AutoCloseable {
    public static class InvalidReply extends IOException { public InvalidReply(String message) { super(message); } }
    private final DeadlineSocket channel;
    private int transaction;
    private long completedNanos;
    private long generation;
    public ModbusReadSession(DeviceEndpoints.Endpoint endpoint) { channel = new DeadlineSocket(endpoint); }
    public synchronized int[] read(int unit, int offset, int count) throws IOException, InterruptedException {
        if (!((unit == 80 && offset == 0x3a && count == 3) || (unit == 80 && offset == 0x40 && count == 7)
                || (unit == 1 && offset == 0 && count == 1) || (unit == 2 && (offset == 0 || offset == 1) && count == 1)))
            throw new IllegalArgumentException("register block not in the sensor profile");
        long remaining = 250_000_000L - (System.nanoTime() - completedNanos);
        if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
        if (!channel.connected()) { channel.connect(); generation++; }
        if (transaction == 65535) { channel.close(); channel.connect(); generation++; transaction = 0; }
        int tid = ++transaction;
        byte[] request = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putShort((short) tid)
                .putShort((short) 0).putShort((short) 6).put((byte) unit).put((byte) 3)
                .putShort((short) offset).putShort((short) count).array();
        try {
            return channel.exchange(request, reader -> {
                ByteBuffer header = ByteBuffer.wrap(reader.read(7)).order(ByteOrder.BIG_ENDIAN);
                int responseTid = Short.toUnsignedInt(header.getShort()), protocol = Short.toUnsignedInt(header.getShort());
                int length = Short.toUnsignedInt(header.getShort()), responseUnit = Byte.toUnsignedInt(header.get());
                if (responseTid != tid || protocol != 0 || responseUnit != unit || length < 3 || length > 253)
                    throw new InvalidReply("Modbus TID/protocol/unit/length mismatch");
                byte[] pdu = reader.read(length - 1);
                if (Byte.toUnsignedInt(pdu[0]) == 0x83) throw new InvalidReply("Modbus exception " + Byte.toUnsignedInt(pdu[1]));
                if (pdu[0] != 3 || length != 3 + count * 2 || Byte.toUnsignedInt(pdu[1]) != count * 2)
                    throw new InvalidReply("Modbus function/byte-count mismatch");
                int[] registers = new int[count];
                ByteBuffer values = ByteBuffer.wrap(pdu, 2, pdu.length - 2).order(ByteOrder.BIG_ENDIAN);
                for (int i = 0; i < count; i++) registers[i] = Short.toUnsignedInt(values.getShort());
                return registers;
            });
        } finally { completedNanos = System.nanoTime(); }
    }
    public long generation() { return generation; }
    public void discardConnection() { channel.close(); }
    @Override public void close() { channel.shutdown(); }
}
