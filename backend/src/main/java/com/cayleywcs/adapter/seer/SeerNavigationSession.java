package com.cayleywcs.adapter.seer;

import com.cayleywcs.adapter.readonly.DeadlineSocket;
import com.cayleywcs.adapter.readonly.DeviceEndpoints;
import com.cayleywcs.common.exception.WcsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** One connection and one 3051 attempt. Never replay a motion command after an uncertain response. */
public final class SeerNavigationSession {
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public record Result(boolean sent,String status,Integer retCode,String message,long receivedAt) { }
    public static String station(String station) {
        if(station==null || station.isBlank() || !station.equals(station.strip()) || station.length()>128
                || station.codePoints().anyMatch(Character::isISOControl))
            throw new WcsException(4223,"站点名称须为当前地图中的站点 ID，长度为 1–128 个字符，不能包含首尾空格或控制字符");
        return station;
    }
    public static Result send(DeviceEndpoints.Endpoint endpoint,String station) {
        station(station);
        try(var channel=new DeadlineSocket(endpoint)) {
            byte[] request=SeerQuerySession.frame(1,3051,JSON.writeValueAsBytes(Map.of("id",station)));
            try { channel.connect(); }
            catch(IOException ex) { return result(false,"failed",null,"导航端口连接失败，未发送指令"); }
            try {
                return channel.exchange(request,reader->{
                    ByteBuffer h=ByteBuffer.wrap(reader.read(16)).order(ByteOrder.BIG_ENDIAN);
                    if(Byte.toUnsignedInt(h.get())!=0x5a || h.get()!=1 || Short.toUnsignedInt(h.getShort())!=1)
                        throw new IOException("navigation response header/sequence mismatch");
                    long length=Integer.toUnsignedLong(h.getInt());int api=Short.toUnsignedInt(h.getShort());
                    int echo=Short.toUnsignedInt(h.getShort());
                    if((api!=13051 && api!=60000) || (echo!=0 && echo!=3051) || h.getInt()!=0 || length<2 || length>SeerQuerySession.MAX_BODY)
                        throw new IOException("navigation response API/length mismatch");
                    var body=JSON.readTree(reader.read((int)length));
                    var code=body==null?null:body.get("ret_code");
                    if(body==null || !body.isObject() || code==null || !code.isIntegralNumber() || !code.canConvertToInt()
                            || api==60000 && code.intValue()==0)throw new IOException("navigation ret_code missing/invalid");
                    boolean accepted=api==13051 && code.intValue()==0;
                    String detail=body.path("err_msg").asText("");
                    return result(accepted,accepted?"accepted":"rejected",code.intValue(),accepted?
                            "设备已确认接收导航指令，尚不代表到站": "设备拒绝导航指令"+(detail.isBlank()?"":"："+detail.substring(0,Math.min(400,detail.length()))));
                });
            } catch(IOException | RuntimeException ex) {
                return result(false,"unknown",null,"未取得有效导航应答，发送结果待核实；请查看车辆状态，不要重复下发");
            }
        } catch(IOException ex) { throw new IllegalStateException("导航报文编码失败",ex); }
    }
    private static Result result(boolean sent,String status,Integer code,String message) {
        return new Result(sent,status,code,message,System.currentTimeMillis());
    }
}
