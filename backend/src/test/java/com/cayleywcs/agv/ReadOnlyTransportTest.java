package com.cayleywcs.agv;

import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.adapter.seer.SeerQuerySession;
import com.cayleywcs.telemetry.gateway.ModbusReadSession;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ReadOnlyTransportTest {
    @Test void actualSeerQueriesAndFourModbusBlocksNeverSendWrites() throws Exception {
        try(var server=new ReadOnlyProtocolSimulator();
            var seer=new SeerQuerySession(new DeviceEndpoints.Endpoint("127.0.0.1",server.seer.getLocalPort(),1000,false));
            var bus=new ModbusReadSession(new DeviceEndpoints.Endpoint("127.0.0.1",server.modbus.getLocalPort(),1000,false))) {
            var location=seer.query(1004);
            assertThat(((Map<?,?>)location.get("payload")).get("current_station")).isEqualTo("测试站");
            seer.query(1005);seer.query(1020);
            assertThatThrownBy(()->seer.query(3051)).isInstanceOf(IllegalArgumentException.class);
            assertThat(bus.read(80,0x3a,3)).containsExactly(60000,123,456);
            assertThat(bus.read(80,0x40,7)).containsExactly(2500,10,20,30,500,600,700);
            assertThat(bus.read(1,0,1)).containsExactly(713);
            assertThat(bus.read(2,1,1)).containsExactly(200);
            assertThat(server.seerApis).containsExactly(1004,1005,1020);
            assertThat(server.reads).extracting(ReadOnlyProtocolSimulator.Request::connection).containsOnly(1);
            assertThat(server.reads).extracting(ReadOnlyProtocolSimulator.Request::unit).containsExactly(80,80,1,2);
            assertThat(server.reads).extracting(ReadOnlyProtocolSimulator.Request::offset).containsExactly(58,64,0,1);
            for(int i=1;i<server.reads.size();i++)assertThat(server.reads.get(i).time()-server.reads.get(i-1).time()).isGreaterThanOrEqualTo(240_000_000L);
            assertThatThrownBy(()->bus.read(80,0x3d,3)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"sequence","api","version","length","reserved","ret","missing","json"})
    void rejectsWrongSeerFramesAndClosesGeneration(String fault) throws Exception {
        try(var server=new ServerSocket(0);var worker=Executors.newVirtualThreadPerTaskExecutor();
            var client=new SeerQuerySession(new DeviceEndpoints.Endpoint("127.0.0.1",server.getLocalPort(),500,false))) {
            var sent=worker.submit(()->{
                try(var socket=server.accept()){
                    byte[] request=socket.getInputStream().readNBytes(16);
                    assertThat(request).isEqualTo(java.util.HexFormat.of().parseHex("5a0100010000000003ec000000000000"));
                    byte[] body=(fault.equals("ret")?"{\"ret_code\":5}":fault.equals("missing")?"{}":fault.equals("json")?"[]":"{\"ret_code\":0}").getBytes(StandardCharsets.UTF_8);
                    ByteBuffer frame=ByteBuffer.allocate(16+body.length).put((byte)0x5a).put((byte)(fault.equals("version")?2:1))
                            .putShort((short)(fault.equals("sequence")?2:1)).putInt(fault.equals("length")?100000:body.length)
                            .putShort((short)(fault.equals("api")?60000:11004)).put(new byte[6]).put(body);
                    if(fault.equals("reserved"))frame.put(10,(byte)1);
                    socket.getOutputStream().write(frame.array());
                }return true;
            });
            assertThatThrownBy(()->client.query(1004)).isInstanceOf(IOException.class);
            assertThat(client.connected()).isFalse();assertThat(sent.get(2,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void absoluteDeadlineStopsTricklingFrame() throws Exception {
        try(var server=new ServerSocket(0);var worker=Executors.newVirtualThreadPerTaskExecutor();
            var client=new SeerQuerySession(new DeviceEndpoints.Endpoint("127.0.0.1",server.getLocalPort(),150,false))) {
            worker.submit(()->{try(var socket=server.accept()){
                socket.getInputStream().readNBytes(16);
                for(byte value:new byte[]{0x5a,1,0,1,0,0,0,20}){socket.getOutputStream().write(value);Thread.sleep(70);}
            }catch(Exception ignored){}});
            long started=System.nanoTime();
            assertThatThrownBy(()->client.query(1004)).isInstanceOf(IOException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(700);
            assertThat(client.connected()).isFalse();
        }
    }
    @Test void invalidModbusTransactionIsNotAcceptedAndNextRequestUsesNewSocket() throws Exception {
        try(var server=new ReadOnlyProtocolSimulator();
            var bus=new ModbusReadSession(new DeviceEndpoints.Endpoint("127.0.0.1",server.modbus.getLocalPort(),200,false))) {
            server.badTid.set(true);
            assertThatThrownBy(()->bus.read(80,0x3a,3)).isInstanceOf(ModbusReadSession.InvalidReply.class);
            assertThat(bus.read(1,0,1)).containsExactly(713);
            assertThat(server.reads).extracting(ReadOnlyProtocolSimulator.Request::connection).containsExactly(1,2);
            server.silence.set(true);
            assertThatThrownBy(()->bus.read(2,0,1)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings={"unit","protocol","length","function","count","exception","truncated"})
    void rejectsMalformedModbusReplies(String fault) throws Exception {
        try(var server=new ServerSocket(0);var worker=Executors.newVirtualThreadPerTaskExecutor();
            var bus=new ModbusReadSession(new DeviceEndpoints.Endpoint("127.0.0.1",server.getLocalPort(),200,false))) {
            var sent=worker.submit(()->{
                try(var socket=server.accept()) {
                    byte[] request=socket.getInputStream().readNBytes(12);
                    assertThat(request).isEqualTo(java.util.HexFormat.of().parseHex("000100000006010300000001"));
                    byte[] response=java.util.HexFormat.of().parseHex("00010000000501030202c9");
                    switch(fault) {
                        case "unit" -> response[6]=2;
                        case "protocol" -> response[3]=1;
                        case "length" -> response[5]=1;
                        case "function" -> response[7]=4;
                        case "count" -> response[8]=4;
                        case "exception" -> {response[7]=(byte)0x83;response[8]=2;}
                        case "truncated" -> response=java.util.Arrays.copyOf(response,10);
                    }
                    socket.getOutputStream().write(response);
                }return true;
            });
            assertThatThrownBy(()->bus.read(1,0,1)).isInstanceOf(IOException.class);
            assertThat(sent.get(2,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void stoppedSessionCannotReconnectOrIssueAnotherQuery() throws Exception {
        try(var server=new ReadOnlyProtocolSimulator();
            var bus=new ModbusReadSession(new DeviceEndpoints.Endpoint("127.0.0.1",server.modbus.getLocalPort(),200,false))) {
            bus.read(1,0,1); bus.close();
            assertThatThrownBy(()->bus.read(1,0,1)).isInstanceOf(IOException.class);
            assertThat(server.reads).hasSize(1);
        }
    }
}
