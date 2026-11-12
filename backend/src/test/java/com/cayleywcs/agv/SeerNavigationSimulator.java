package com.cayleywcs.agv;

import com.cayleywcs.adapter.seer.SeerQuerySession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only two-port SRC fixture. Records real 3051 bytes and never connects to field equipment. */
final class SeerNavigationSimulator implements AutoCloseable {
    final ServerSocket state, navigation;
    final List<Map<String,Object>> commands=new CopyOnWriteArrayList<>();
    final List<Integer> stateApis=new CopyOnWriteArrayList<>();
    volatile String mode="ok";
    volatile boolean moving;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json=new ObjectMapper();
    SeerNavigationSimulator() {
        try {state=new ServerSocket(0,20,InetAddress.getLoopbackAddress());navigation=new ServerSocket(0,20,InetAddress.getLoopbackAddress());}
        catch(IOException ex){throw new UncheckedIOException(ex);}
        workers.execute(()->accept(state,false));workers.execute(()->accept(navigation,true));
    }
    private void accept(ServerSocket server,boolean command) {
        while(!server.isClosed())try {Socket socket=server.accept();sockets.add(socket);workers.execute(()->serve(socket,command));}
        catch(IOException ignored){return;}
    }
    private void serve(Socket socket,boolean command) {
        try(socket) {
            var in=new DataInputStream(socket.getInputStream());var out=socket.getOutputStream();
            while(true) {
                byte[] raw=in.readNBytes(16);if(raw.length!=16)return;
                var h=ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);int sequence=Short.toUnsignedInt(h.getShort(2)),length=h.getInt(4),api=Short.toUnsignedInt(h.getShort(8));
                if(raw[0]!=0x5a || raw[1]!=1 || length<0 || length>4096 || h.getShort(10)!=0 || h.getInt(12)!=0)return;
                var body=in.readNBytes(length);if(body.length!=length)return;
                Object reply;
                String responseMode=mode;
                if(command) {
                    if(api!=3051)return;
                    commands.add(json.readValue(body,Map.class));
                    if(responseMode.equals("timeout"))continue;
                    reply=switch(responseMode) {
                        case "reject","generic" -> Map.of("ret_code",60002,"err_msg","站点不存在");
                        case "missing" -> Map.of("err_msg","");
                        case "string" -> Map.of("ret_code","0");
                        default -> Map.of("ret_code",0);
                    };
                } else {
                    if(length!=0)return;stateApis.add(api);
                    reply=switch(api) {
                        case 1004 -> Map.of("ret_code",0,"x",1.25,"y",2.5,"angle",0.1,"confidence",0.95,"current_station","AP0");
                        case 1005 -> Map.of("ret_code",0,"vx",moving?0.2:0.0,"vy",0,"w",0,"is_stop",!moving);
                        case 1020 -> Map.of("ret_code",0,"task_status",moving?2:4,"target_id","AP0");
                        default -> throw new IOException("unexpected state API");
                    };
                }
                byte[] frame=SeerQuerySession.frame(command && responseMode.equals("sequence")?sequence+1:sequence,
                        command && responseMode.equals("generic")?60000:api+10000,json.writeValueAsBytes(reply));
                // SRC echo request API; deliberately fragmented header and payload.
                ByteBuffer.wrap(frame).putShort(10,(short)api);
                out.write(frame,0,5);out.flush();out.write(frame,5,frame.length-5);out.flush();
            }
        } catch(IOException ignored) { } finally {sockets.remove(socket);}
    }
    @Override public void close() {
        try {state.close();navigation.close();} catch(IOException ignored) { }
        for(var socket:sockets)try{socket.close();}catch(IOException ignored){}
        workers.shutdownNow();
    }
}
