package com.cayleywcs.adapter;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PointTypeRegistryTest {
    static Stream<Arguments> orderedPairs() {
        return PointTypeRegistry.PROTOCOLS.stream().flatMap(source -> PointTypeRegistry.PROTOCOLS.stream()
                .flatMap(target -> Stream.of("request", "response").map(direction -> Arguments.of(source, target, direction))));
    }
    @ParameterizedTest @MethodSource("orderedPairs")
    void everyOrderedProtocolPairChecksAliasesAndRejectsLossyTypes(String source, String target, String direction) {
        assertThat(PointTypeRegistry.compatible(source, "INT", target, "INT16")).as(source + " → " + target + " / " + direction).isTrue();
        assertThat(PointTypeRegistry.compatible(source, "REAL", target, "DOUBLE")).isFalse();
        assertThat(PointTypeRegistry.compatible(source, "INT", target, "BOOL")).isFalse();
        assertThat(PointTypeRegistry.compatible(source, "UINT", target, "INT")).isFalse();
        assertThat(PointTypeRegistry.decode(target, "INT16", PointTypeRegistry.encode(source, "INT", 32767))).isEqualTo(32767L);
        assertThatThrownBy(() -> PointTypeRegistry.encode(source, "INT", 32768)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PointTypeRegistry.compatible(source, "TEXT", target, "string")).isEqualTo(!source.equals("modbus_tcp") && !target.equals("modbus_tcp"));
    }
    @Test void pairMatrixCoversCommonTransportsAndSimulationOnly() {
        assertThat(PointTypeRegistry.PROTOCOLS).containsExactly("http","opcua","modbus_tcp","s7","mqtt","tcp","sim","loopback");
        assertThat(PointTypeRegistry.pairMatrix("INT", "INT16")).hasSize(64);
        assertThat(PointTypeRegistry.pairMatrix("INT", "INT16").stream().filter(row -> row.get("source_protocol").equals(row.get("target_protocol")))).hasSize(8);
        assertThat(PointTypeRegistry.compatible("unregistered", "INT", "http", "INT")).isFalse();
    }
    @Test void exactIntegerBoundariesNeverTruncateFractionsOrOverflow() {
        assertThat(PointTypeRegistry.encode("opcua", "UINT16", 65535)).isEqualTo(65535);
        assertThat(PointTypeRegistry.encode("opcua", "INT16", -32768)).isEqualTo((short)-32768);
        for (Object value : List.of(65536, -1, new BigDecimal("1.1"))) assertThatThrownBy(() -> PointTypeRegistry.encode("opcua", "UINT16", value)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PointTypeRegistry.encode("http", "INT64", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(PointTypeRegistry.decode("http", "UINT64", new BigInteger("18446744073709551615"))).isEqualTo(new BigInteger("18446744073709551615"));
        assertThatThrownBy(() -> PointTypeRegistry.encode("http", "UINT64", new BigInteger("18446744073709551616"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointTypeRegistry.canonical("opcua", "UINT64")).hasMessageContaining("不能无损");
        assertThatThrownBy(() -> PointTypeRegistry.canonical("opcua", "ARRAY[2] OF ULINT")).hasMessageContaining("不能无损");
        assertThat(PointTypeRegistry.options("opcua")).doesNotContain("UINT64");
        assertThatThrownBy(() -> PointTypeRegistry.encode("sim", "INT", "123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointTypeRegistry.encode("sim", "INT", new BigDecimal("1e1000000000"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void booleanAndUnknownTypesDoNotUseStringFallback() {
        assertThat(PointTypeRegistry.encode("mqtt", "BOOLEAN", true)).isEqualTo(true);
        for (Object raw : List.of(1, 2, "true", "false", "garbage")) assertThatThrownBy(() -> PointTypeRegistry.encode("mqtt", "BOOL", raw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointTypeRegistry.encode("http", "ANY", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointTypeRegistry.encode("s7", "STRING", 9)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void typedArraysPreserveElementsAndDeclaredDimensions() {
        assertThat(PointTypeRegistry.decode("http", "ARRAY[2] OF BOOL", new boolean[]{true, false})).isEqualTo(List.of(true, false));
        assertThat(PointTypeRegistry.decode("tcp", "ARRAY[1..2] OF TEXT", List.of("礁盘", "é"))).isEqualTo(List.of("礁盘", "é"));
        assertThat(PointTypeRegistry.decode("s7", "ARRAY[2,2] OF INT", List.of(List.of(1,2), List.of(3,4)))).isEqualTo(List.of(List.of(1L,2L), List.of(3L,4L)));
        assertThatThrownBy(() -> PointTypeRegistry.encode("sim", "ARRAY[2] OF INT", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointTypeRegistry.encode("sim", "ARRAY[2] OF INT", List.of(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointTypeRegistry.encode("sim", "ARRAY[2] OF INT", List.of(1, "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointTypeRegistry.canonical("sim", "ARRAY[-2147483648..2147483647] OF INT")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void floatsRejectNonFiniteValuesAndUnderflow() {
        for (Object raw : List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, new BigDecimal("1e1000"), new BigDecimal("1e-1000"))) {
            assertThatThrownBy(() -> PointTypeRegistry.encode("opcua", "REAL", raw)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(PointTypeRegistry.encode("opcua", "REAL", 1.25)).isEqualTo(1.25f);
    }
}
