package com.cayleywcs.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.cayleywcs.adapter.plc4x.Plc4xAdapter;
import com.cayleywcs.application.entity.ApplicationEntity;
import com.cayleywcs.protocol.entity.ProtocolEntity;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in: a real Snap7 / Modbus server must be started on isolated loopback ports. */
@EnabledIfEnvironmentVariable(named="WCS_EXTERNAL_SIMULATORS", matches="true")
class ExternalPlcTransportTest {
    public static void main(String[] args) throws Exception {
        var tests = new ExternalPlcTransportTest();
        tests.s7ReadsAndWritesNativeSignedWordsAndArrays();
        System.out.println("S7_REAL_NETWORK_PASS signed=-2345 array=[-32768,0,32767]");
        tests.modbusReadsAndWritesSignedAndMultiRegisterValuesAndCoils();
        System.out.println("MODBUS_REAL_NETWORK_PASS signed=-12345 dint=-123456789 coil=true exception=verified");
    }
    @Test void s7ReadsAndWritesNativeSignedWordsAndArrays() throws Exception {
        var adapter = new Plc4xAdapter("s7", "s7://127.0.0.1:21102?remote-rack=0&remote-slot=1&controller-type=S7_300", 5000);
        var number = point("signed", "%DB100:0:INT", "INT", "request");
        var array = point("numbers", "%DB100:8:INT[3]", "ARRAY[3] OF INT", "request");
        adapter.connect(context("s7", List.of(number, array), Map.of()));
        try {
            adapter.write(number, -2345);
            assertThat(((Number)adapter.read(number)).intValue()).isEqualTo(-2345);
            adapter.write(array, List.of(-32768, 0, 32767));
            assertThat(adapter.read(array)).isEqualTo(List.of(-32768L, 0L, 32767L));
            var alias=point("alias", "%DB100:24:SHORT", "SHORT", "request");
            adapter.write(alias,-1234); assertThat(adapter.read(alias)).isEqualTo(-1234L);
            var bitAlias=point("flag", "%DB100:64.0:BIT", "BOOLEAN", "request");
            adapter.write(bitAlias,true); assertThat(adapter.read(bitAlias)).isEqualTo(true);
            var wideUnsigned=point("unsigned64", "%DB100:80:ULINT", "UINT64", "request");
            var maximum=new java.math.BigInteger("18446744073709551615");
            adapter.write(wideUnsigned,maximum); assertThat(adapter.read(wideUnsigned)).isEqualTo(maximum);
            assertThatThrownBy(() -> adapter.write(number, 32768)).isInstanceOf(RuntimeException.class);
        } finally { adapter.disconnect(); }
    }

    @Test void modbusReadsAndWritesSignedAndMultiRegisterValuesAndCoils() throws Exception {
        var adapter = new Plc4xAdapter("modbus_tcp", "modbus-tcp:tcp://127.0.0.1:21502?unit-identifier=1", 5000);
        var word = point("word", "holding-register:1:INT", "INT", "request");
        var wide = point("wide", "holding-register:3:DINT", "DINT", "request");
        var coil = point("coil", "coil:1:BOOL", "BOOL", "request");
        adapter.connect(context("modbus_tcp", List.of(word, wide, coil), Map.of()));
        try {
            adapter.write(word, -12345);
            assertThat(((Number)adapter.read(word)).intValue()).isEqualTo(-12345);
            adapter.write(wide, -123456789);
            assertThat(((Number)adapter.read(wide)).intValue()).isEqualTo(-123456789);
            adapter.write(coil, true);
            assertThat(adapter.read(coil)).isEqualTo(true);
            var unsigned64=point("unsigned64","holding-register:40:ULINT","UINT64","request");
            var maximum=new java.math.BigInteger("18446744073709551615");
            adapter.write(unsigned64,maximum); assertThat(adapter.read(unsigned64)).isEqualTo(maximum);
            for (String bytes : List.of("BIG_ENDIAN", "LITTLE_ENDIAN")) {
                for (String words : List.of("BIG_ENDIAN", "LITTLE_ENDIAN")) {
                    var ordered = point("ordered", "holding-register:20:DINT", "DINT", "request");
                    ordered.setConstraints(Map.of("byte_order", bytes, "word_order", words));
                    adapter.write(ordered, -16909060);
                    assertThat(adapter.read(ordered)).isEqualTo(-16909060L);
                    // Check the actual server words independently from the inverse codec.
                    Object actual = adapter.read(point("raw", "holding-register:20:UINT[2]", "ARRAY[2] OF UINT", "response"));
                    byte[] binary = java.nio.ByteBuffer.allocate(4).putInt(-16909060).array();
                    int first = "LITTLE_ENDIAN".equals(words) ? 2 : 0;
                    int last = 2-first;
                    assertThat(actual).isEqualTo(List.of(word(binary, first, bytes), word(binary, last, bytes)));
                }
            }
            assertThatThrownBy(() -> adapter.read(point("missing", "holding-register:60000:INT", "INT", "response")))
                    .isInstanceOf(RuntimeException.class);
        } finally { adapter.disconnect(); }
    }

    private static long word(byte[] binary, int offset, String byteOrder) {
        int high = Byte.toUnsignedInt(binary[offset]); int low = Byte.toUnsignedInt(binary[offset+1]);
        return "LITTLE_ENDIAN".equals(byteOrder) ? (low<<8)|high : (high<<8)|low;
    }

    static ProtocolPointEntity point(String field, String address, String type, String direction) {
        var point = new ProtocolPointEntity();
        point.setField_name(field); point.setAddress(address); point.setData_type(type);
        point.setDirection(direction); point.setPoint_scope("business");
        point.setOperation("request".equals(direction) ? "dispatch" : "result");
        return point;
    }
    static AdapterContext context(String type, List<ProtocolPointEntity> points, Map<String,Object> params) {
        var app = new ApplicationEntity(); app.setApp_code("ISOLATED_TEST"); app.setConn_params(params);
        var protocol = new ProtocolEntity(); protocol.setProtocol_type(type); protocol.setProtocol_code("TEST_"+type);
        return new AdapterContext(app, protocol, points);
    }
}
