package com.cayleywcs.agv;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real TCP servers; every request is captured for exact wire assertions. */
public final class ReadOnlyProtocolSimulator implements AutoCloseable {
    public record Request(int unit,int offset,int count,long time,int connection) { }
    public final ServerSocket seer, modbus;
    public final List<Integer> seerApis=new CopyOnWriteArrayList<>();
    public final List<Request> reads=new CopyOnWriteArrayList<>();
    public final AtomicBoolean silence=new AtomicBoolean(), badTid=new AtomicBoolean();
    public final java.util.concurrent.atomic.AtomicInteger silentUnit=new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public ReadOnlyProtocolSimulator() {
        try { seer=new ServerSocket(0,32,InetAddress.getLoopbackAddress()); modbus=new ServerSocket(0,32,InetAddress.getLoopbackAddress()); }
        catch(IOException ex){throw new UncheckedIOException(ex);}
        workers.execute(()->accept(seer,true)); workers.execute(()->accept(modbus,false));
    }
    private void accept(ServerSocket server,boolean isSeer) {
        int connection=0;
        while(!closed)try {
            Socket socket=server.accept(); sockets.add(socket); int id=++connection;
            workers.execute(()->serve(socket,isSeer,id));
        }catch(IOException ex){if(!closed)throw new UncheckedIOException(ex);}
    }
    private void serve(Socket socket,boolean isSeer,int connection) {
        try(socket) {
            var in=new DataInputStream(socket.getInputStream());var out=socket.getOutputStream();
            while(!closed) {
                byte[] request=in.readNBytes(isSeer?16:12);if(request.length==0)return;
                if(request.length!=(isSeer?16:12))throw new IOException("short request");
                ByteBuffer bytes=ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN);
                if(isSeer) {
                    int seq=Short.toUnsignedInt(bytes.getShort(2)), length=bytes.getInt(4),api=Short.toUnsignedInt(bytes.getShort(8));
                    if(bytes.get(0)!=0x5a || bytes.get(1)!=1 || length!=0)throw new IOException("unexpected SEER query");
                    seerApis.add(api);
                    String response=switch(api){
                        case 1004 -> "{\"ret_code\":0,\"x\":1.25,\"y\":2.5,\"angle\":0.1,\"current_station\":\"测试站\"}";
                        case 1005 -> "{\"ret_code\":0,\"vx\":0.2,\"vy\":0,\"w\":0}";
                        case 1020 -> "{\"ret_code\":0,\"task_status\":2,\"target_id\":\"AP0\"}";
                        default -> throw new IOException("unexpected motion API "+api);
                    };
                    byte[] body=response.getBytes(StandardCharsets.UTF_8);
                    byte[] frame=ByteBuffer.allocate(16+body.length).put((byte)0x5a).put((byte)1).putShort((short)seq)
                            .putInt(body.length).putShort((short)(api+10000)).putShort((short)api).putInt(0).put(body).array();
                    // TCP fragmentation is intentional.
                    out.write(frame,0,9);out.flush();out.write(frame,9,frame.length-9);out.flush();
                } else {
                    int tid=Short.toUnsignedInt(bytes.getShort()),protocol=Short.toUnsignedInt(bytes.getShort()),len=Short.toUnsignedInt(bytes.getShort());
                    int unit=Byte.toUnsignedInt(bytes.get()),fc=Byte.toUnsignedInt(bytes.get()),offset=Short.toUnsignedInt(bytes.getShort()),count=Short.toUnsignedInt(bytes.getShort());
                    if(protocol!=0 || len!=6 || fc!=3)throw new IOException("unexpected Modbus command");
                    reads.add(new Request(unit,offset,count,System.nanoTime(),connection));
                    if(silence.get() || silentUnit.get()==unit)continue;
                    int[] values=unit==80 && offset==0x3a?new int[]{60000,123,456}:unit==80 && offset==0x40?new int[]{2500,10,20,30,500,600,700}:unit==1?new int[]{713}:new int[]{200};
                    if(values.length!=count)throw new IOException("unexpected read block");
                    ByteBuffer reply=ByteBuffer.allocate(9+count*2).putShort((short)(badTid.getAndSet(false)?tid+1:tid))
                            .putShort((short)0).putShort((short)(3+count*2)).put((byte)unit).put((byte)3).put((byte)(count*2));
                    for(int value:values)reply.putShort((short)value);
                    out.write(reply.array());out.flush();
                }
            }
        }catch(IOException ignored){}finally{sockets.remove(socket);}
    }
    @Override public void close() {
        closed=true;
        try{seer.close();modbus.close();}catch(IOException ignored){}
        sockets.forEach(socket->{try{socket.close();}catch(IOException ignored){}});
        workers.shutdownNow();
    }
}
