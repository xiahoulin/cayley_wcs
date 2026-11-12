package com.cayleywcs.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.context;
import static com.cayleywcs.adapter.ExternalPlcTransportTest.point;
import com.cayleywcs.adapter.http.HttpAdapter;
import com.cayleywcs.adapter.message.PointMessage;
import com.cayleywcs.adapter.tcp.TcpAdapter;
import com.cayleywcs.common.exception.WcsException;
import com.sun.net.httpserver.HttpServer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PointMessageTransportTest {
    public static void main(String[] args) throws Exception {
        var tests = new PointMessageTransportTest();
        tests.httpUsesPostPayloadAndChecksStatusCorrelationAndJson();
        tests.tcpReadsWritesFragmentedFramesAndRejectsInvalidCorrelation();
        tests.tcpRejectsOversizedFrameAndClosesTimedOutChannel();
        tests.rejectsDuplicatePayloadKeysAndStaleReplies();
        tests.completeOperationTimeoutIncludesSlowPartialResponses();
        System.out.println("HTTP_TCP_REAL_NETWORK_PASS POST_payload/status/JSON/correlation/fragmentation/frame_limit/timeout");
    }

    @Test void httpUsesPostPayloadAndChecksStatusCorrelationAndJson() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var value = new AtomicReference<Object>(0);
        server.createContext("/points", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            PointMessage request = PointMessage.parse(exchange.getRequestBody().readAllBytes());
            if (request.operation().equals("write")) { value.set(request.payload().get("speed")); }
            byte[] response = reply(request, value.get(), request.correlation_id()).bytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/bad-status", exchange -> {
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.createContext("/bad-json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] body = "not json".getBytes(); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/bad-correlation", exchange -> {
            PointMessage request = PointMessage.parse(exchange.getRequestBody().readAllBytes());
            byte[] body = reply(request, 100, "wrong").bytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var adapter = new HttpAdapter();
        var speed = point("speed", "/points", "INT16", "request");
        adapter.connect(context("http", List.of(speed), Map.of("baseUrl", "http://127.0.0.1:"+server.getAddress().getPort())));
        try {
            adapter.write(speed, -2345); assertThat(adapter.read(speed)).isEqualTo(-2345L);
            for (String path : List.of("/bad-status", "/bad-json", "/bad-correlation")) {
                assertThatThrownBy(() -> adapter.read(point("speed", path, "INT16", "response")))
                        .isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode())
                                .isEqualTo(path.equals("/bad-status")?4222:4225));
            }
            // 适配器执行驱动显式发出的操作；业务方向由调用层决定，不再使用独立权限字段。
            adapter.write(speed, 2);
            assertThat(adapter.read(speed)).isEqualTo(2L);
        } finally { adapter.disconnect(); server.stop(0); }
    }

    @Test void tcpReadsWritesFragmentedFramesAndRejectsInvalidCorrelation() throws Exception {
        try (var server = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
             var worker = Executors.newSingleThreadExecutor()) {
            var exchange = worker.submit(() -> {
                try (var socket = server.accept()) {
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    Object value = 0;
                    for (int i=0; i<3; i++) {
                        int length = input.readInt(); byte[] bytes = new byte[length]; input.readFully(bytes);
                        PointMessage request = PointMessage.parse(bytes);
                        if (request.operation().equals("write")) { value = request.payload().get("speed"); }
                        byte[] response = reply(request, value, i==2 ? "wrong" : request.correlation_id()).bytes();
                        byte[] header = java.nio.ByteBuffer.allocate(4).putInt(response.length).array();
                        for (byte part : header) { output.write(part); output.flush(); }
                        for (int offset=0; offset<response.length; offset+=7) {
                            output.write(response, offset, Math.min(7, response.length-offset)); output.flush();
                        }
                    }
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            var adapter = new TcpAdapter(); var speed = point("speed", "speed", "INT16", "request");
            adapter.connect(context("tcp", List.of(speed), Map.of("host", "127.0.0.1", "port", server.getLocalPort())));
            try {
                adapter.write(speed, -1234); assertThat(adapter.read(speed)).isEqualTo(-1234L);
                assertThatThrownBy(() -> adapter.read(speed)).isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4225));
                assertThat(adapter.isConnected()).isFalse(); exchange.get();
            } finally { adapter.disconnect(); }
        }
    }

    @Test void tcpRejectsOversizedFrameAndClosesTimedOutChannel() throws Exception {
        for (boolean oversized : List.of(true, false)) {
            try (var server = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
                 var worker = Executors.newSingleThreadExecutor()) {
                var exchange = worker.submit(() -> {
                    try (var socket = server.accept()) {
                        var input = new DataInputStream(socket.getInputStream());
                        int length = input.readInt(); input.readNBytes(length);
                        if (oversized) { new DataOutputStream(socket.getOutputStream()).writeInt(Integer.MAX_VALUE); }
                        else { Thread.sleep(400); }
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                });
                var adapter = new TcpAdapter(); var point = point("speed", "speed", "INT16", "request");
                adapter.connect(context("tcp", List.of(point), Map.of("host", "127.0.0.1", "port", server.getLocalPort(), "requestTimeoutMs", 100)));
                assertThatThrownBy(() -> adapter.read(point)).isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(oversized?4225:4222));
                assertThat(adapter.isConnected()).isFalse(); exchange.get(); adapter.disconnect();
            }
        }
    }

    @Test void rejectsDuplicatePayloadKeysAndStaleReplies() {
        String duplicate = "{\"version\":1,\"operation\":\"read\",\"correlation_id\":\"id\",\"direction\":\"response\",\"payload\":{\"x\":1,\"x\":2},\"timestamp\":1}";
        assertThatThrownBy(() -> PointMessage.parse(duplicate.getBytes())).isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4225));
        var request = PointMessage.request("read", "speed", null);
        var stale = new PointMessage(1, "read", request.correlation_id(), "response", Map.of("speed", 2), 1, null);
        assertThatThrownBy(() -> stale.responseValue(request, "speed")).isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4225));
    }

    @Test void completeOperationTimeoutIncludesSlowPartialResponses() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try (var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/slow", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,0);
                try {
                    for (int i=0;i<40;i++) { exchange.getResponseBody().write(' '); exchange.getResponseBody().flush(); Thread.sleep(15); }
                } catch (Exception expectedOnClientTimeout) { }
                finally { exchange.close(); }
            });
            server.createContext("/oversized", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,1024); exchange.getResponseBody().write(new byte[1024]); exchange.close();
            });
            server.start();
            var adapter=new HttpAdapter(); var speed=point("speed","/slow","INT16","request");
            adapter.connect(context("http",List.of(speed),Map.of("baseUrl","http://127.0.0.1:"+server.getAddress().getPort(),
                    "requestTimeoutMs",100,"maxResponseBytes",256)));
            try {
                long started=System.nanoTime();
                assertThatThrownBy(()->adapter.read(speed)).isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4222));
                assertThat((System.nanoTime()-started)/1000000).isLessThan(500);
                assertThatThrownBy(()->adapter.read(point("speed","/oversized","INT16","response"))).isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4225));
            } finally { adapter.disconnect(); server.stop(0); }
        }
        try (var tcp=new ServerSocket(0,5,java.net.InetAddress.getLoopbackAddress()); var worker=Executors.newSingleThreadExecutor()) {
            var device=worker.submit(()->{
                try(var socket=tcp.accept()) {
                    var input=new DataInputStream(socket.getInputStream()); int length=input.readInt(); input.readNBytes(length);
                    var output=new DataOutputStream(socket.getOutputStream()); output.writeInt(1000); output.flush();
                    for(int i=0;i<1000;i++){output.write(' ');output.flush();Thread.sleep(10);}
                } catch(Exception expectedOnClientTimeout) { }
            });
            var adapter=new TcpAdapter(); var speed=point("speed","speed","INT16","response");
            adapter.connect(context("tcp",List.of(speed),Map.of("host","127.0.0.1","port",tcp.getLocalPort(),"requestTimeoutMs",100)));
            long started=System.nanoTime();
            assertThatThrownBy(()->adapter.read(speed)).isInstanceOfSatisfying(WcsException.class,error->assertThat(error.getCode()).isEqualTo(4222));
            assertThat((System.nanoTime()-started)/1000000).isLessThan(500);
            assertThat(adapter.isConnected()).isFalse(); device.get(); adapter.disconnect();
        }
    }

    static PointMessage reply(PointMessage request, Object value, String correlation) {
        return new PointMessage(1, request.operation(), correlation, "response", Map.of("speed", value),
                System.currentTimeMillis(), null);
    }
}
