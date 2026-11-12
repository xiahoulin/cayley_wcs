package com.cayleywcs.adapter.readonly;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One in-flight request, an absolute deadline, and fresh TCP generation after any failure. */
public final class DeadlineSocket implements AutoCloseable {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("readonly-io-deadline").factory());
    private final DeviceEndpoints.Endpoint endpoint;
    private volatile Socket socket;
    private volatile boolean terminated;
    public DeadlineSocket(DeviceEndpoints.Endpoint endpoint) { this.endpoint = endpoint; }
    public boolean connected() { Socket s = socket; return s != null && !s.isClosed(); }
    public synchronized void connect() throws IOException {
        if (terminated) throw new IOException("session stopped");
        if (connected()) return;
        Socket candidate = new Socket(); socket = candidate;
        try {
            candidate.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), endpoint.timeoutMs());
            if (terminated) { candidate.close(); throw new IOException("session stopped"); }
            candidate.setTcpNoDelay(true); candidate.setSoTimeout(endpoint.timeoutMs());
        } catch (IOException ex) { close(); throw ex; }
    }
    @FunctionalInterface public interface Reply<T> { T read(Reader reader) throws IOException; }
    public synchronized <T> T exchange(byte[] request, Reply<T> reply) throws IOException {
        connect();
        Socket active = socket;
        var deadline = DEADLINES.schedule(() -> closeSocket(active), endpoint.timeoutMs(), TimeUnit.MILLISECONDS);
        long expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(endpoint.timeoutMs());
        try {
            if (terminated) throw new IOException("session stopped");
            active.getOutputStream().write(request);
            T result = reply.read(length -> {
                byte[] data = new byte[length]; int offset = 0;
                while (offset < length) {
                    long remaining = expires - System.nanoTime();
                    if (remaining <= 0) throw new java.net.SocketTimeoutException("absolute response deadline");
                    active.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    int count = active.getInputStream().read(data, offset, length - offset);
                    if (count < 0) throw new EOFException("incomplete response");
                    offset += count;
                }
                return data;
            });
            if (System.nanoTime() >= expires) throw new java.net.SocketTimeoutException("absolute response deadline");
            return result;
        } catch (IOException | RuntimeException ex) { close(); throw ex; }
        finally { deadline.cancel(false); }
    }
    @FunctionalInterface public interface Reader { byte[] read(int length) throws IOException; }
    @Override public void close() { Socket active = socket; socket = null; closeSocket(active); }
    public void shutdown() { terminated = true; close(); }
    private static void closeSocket(Socket active) {
        if (active != null) try { active.close(); } catch (IOException ignored) { }
    }
}
