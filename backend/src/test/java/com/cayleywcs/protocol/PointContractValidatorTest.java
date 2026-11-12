package com.cayleywcs.protocol;

import static org.assertj.core.api.Assertions.*;
import com.cayleywcs.protocol.contract.PointContractValidator;
import com.cayleywcs.protocol.contract.ProtocolContractService.Snapshot;
import com.cayleywcs.protocol.contract.impl.ProtocolContractServiceImpl;
import com.cayleywcs.protocol.entity.ProtocolPointEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PointContractValidatorTest {
    static ProtocolPointEntity point(String name, String type, String nativeType, String direction, Map<String,Object> constraints) {
        ProtocolPointEntity p = new ProtocolPointEntity(); p.setField_name(name); p.setTemplate_code("auto_door"); p.setTemplate_version(1L);
        p.setOperation(direction.equals("request") ? "dispatch" : "result"); p.setDirection(direction); p.setPoint_scope("business");
        p.setLogical_type(type); p.setData_type(nativeType); p.setAddress("payload." + name);
        p.setRequired(true); p.setNullable(false); p.setConstraints(constraints); return p;
    }
    static Snapshot snapshot(String protocol, List<ProtocolPointEntity> points) { return new Snapshot(1L, 1L, 1, protocol, "auto_door", points, "f"); }
    @Test void exactFieldsDirectionAndFullConstraintsMustMatch() {
        var a = point("field", "STRING", "TEXT", "request", Map.of("encoding","UTF-8","max_length",20));
        var b = point("field", "string", "STRING", "request", Map.of("encoding","UTF-8","max_length",20L));
        b.setAddress("ns=2;s=field");
        assertThat(ProtocolContractServiceImpl.compare(snapshot("http", List.of(a)), snapshot("opcua", List.of(b)))).isEmpty();
        b.setDirection("response"); b.setOperation("result");
        assertThat(ProtocolContractServiceImpl.compare(snapshot("http", List.of(a)), snapshot("opcua", List.of(b)))).hasSize(2);
        b.setDirection("request"); b.setOperation("dispatch"); b.setConstraints(Map.of("encoding","UTF-8","max_length",19));
        assertThat(ProtocolContractServiceImpl.compare(snapshot("http", List.of(a)), snapshot("opcua", List.of(b)))).hasSize(1);
    }
    @Test void declaredIntegerWidthMayDifferOnlyWithSameCompleteSafeDomain() {
        var a = point("field", "INT16", "integer", "response", Map.of("min",0,"max",100));
        var b = point("field", "INT16", "INT", "response", Map.of("min",0,"max",100));
        b.setAddress("%DB100:0:INT");
        assertThat(ProtocolContractServiceImpl.compare(snapshot("http", List.of(a)), snapshot("s7", List.of(b)))).isEmpty();
        b.setConstraints(Map.of("min",0,"max",101));
        assertThat(ProtocolContractServiceImpl.compare(snapshot("http", List.of(a)), snapshot("s7", List.of(b)))).isNotEmpty();
        a.setConstraints(Map.of("min",0,"max",65534));
        assertThatThrownBy(() -> PointContractValidator.definition("http", a)).hasMessageContaining("原生");
    }
    @Test void doorPayloadRejectsMissingExtraAndBothOrNeitherActions() {
        List<ProtocolPointEntity> points = List.of(point("cmd_Open","INT16","INT","request",Map.of("min",0,"max",1,"enum",List.of(0,1))), point("cmd_Close","INT16","INT","request",Map.of("min",0,"max",1,"enum",List.of(0,1))));
        assertThat(PointContractValidator.payload("sim","auto_door",points,Map.of("cmd_Open",1,"cmd_Close",0),"request")).containsEntry("cmd_Open",(short)1);
        for (Map<String,Object> raw : List.<Map<String,Object>>of(Map.of("cmd_Open",1),Map.of("cmd_Open",1,"cmd_Close",1),Map.of("cmd_Open",0,"cmd_Close",0),Map.of("cmd_Open",1,"cmd_Close",0,"WCS_Heart",1))) assertThatThrownBy(() -> PointContractValidator.payload("sim","auto_door",points,raw,"request")).isInstanceOf(com.cayleywcs.common.exception.WcsException.class);
    }
    @Test void nullEnumUnicodeArrayAndWireEncodingAreExplicit() {
        var str = point("field","STRING","TEXT","response",Map.of("encoding","UTF-8","max_length",2));
        assertThat(PointContractValidator.value("http",str,"中文",4225)).isEqualTo("中文");
        assertThatThrownBy(() -> PointContractValidator.value("http",str,"中文长",4225)).hasMessageContaining("过长");
        assertThatThrownBy(() -> PointContractValidator.value("http",str,null,4225)).hasMessageContaining("空值");
        var wide = point("wide","INT64","INT64","response",Map.of("wire_encoding","decimal_string"));
        PointContractValidator.definition("http",wide);
        assertThat(PointContractValidator.value("http",wide,"9223372036854775807",4225)).isEqualTo("9223372036854775807");
        assertThat(PointContractValidator.value("http",wide,Long.MAX_VALUE,4225)).isEqualTo("9223372036854775807");
        assertThatThrownBy(() -> PointContractValidator.value("http",wide,Long.MAX_VALUE,4241)).hasMessageContaining("十进制字符串");
        var array = point("codes","ARRAY<INT16>","ARRAY[2] OF INT","response",Map.of("dimensions",List.of(2),"length",2));
        array.setAddress("ns=2;s=codes");
        assertThat(PointContractValidator.value("opcua",array,List.of(1,2),4225)).isEqualTo(List.of((short)1,(short)2));
        assertThatThrownBy(() -> PointContractValidator.value("opcua",array,List.of(1),4225)).hasMessageContaining("长度");
    }
    @Test void scaleAndUnknownConstraintFailPublicationRatherThanBeingIgnored() {
        var p = point("field","INT16","INT","request",Map.of("bogus",1));
        p.setAddress("ns=2;s=field");
        assertThatThrownBy(() -> PointContractValidator.definition("opcua",p)).hasMessageContaining("未知约束");
        p.setConstraints(Map.of()); p.setScale(new java.math.BigDecimal("0.1"));
        assertThatThrownBy(() -> PointContractValidator.definition("opcua",p)).hasMessageContaining("缩放");
    }
}
