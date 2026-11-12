package com.cayleywcs.agv;

import com.cayleywcs.telemetry.profile.SensorProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SensorProfileTest {
    @Test void unsignedScalingAndNoUndefinedRegisters() {
        var plan=SensorProfile.plan(0);
        var speeds=SensorProfile.decode(plan.get(0),new int[]{60000,65535,0},1000,null,0,600,1,Map.of());
        assertThat(speeds.get("vibration_speed_x").value()).isEqualTo(600.0);
        assertThat(speeds.get("vibration_speed_y").value()).isEqualTo(655.35);
        var detail=SensorProfile.decode(plan.get(1),new int[]{65500,10,20,30,500,600,700},1000,null,0,600,1,Map.of());
        assertThat(detail.get("vibration_chip_temperature").quality()).isEqualTo("UNKNOWN");
        assertThat(detail.get("vibration_chip_temperature").value()).isNull();
        assertThat(detail.get("vibration_displacement_x").value()).isEqualTo(10.0);
        assertThat(detail.get("vibration_frequency_x").value()).isEqualTo(50.0);
        assertThat(plan.stream().mapToInt(block->block.metrics().size()).sum()).isEqualTo(12);
    }
    @Test void warmupUnknownAndFortyMinuteBoundaryAreNotGood() {
        var block=SensorProfile.plan(1).get(3);
        long power=1000, warm=power+SensorProfile.WARMUP_MS;
        assertThat(SensorProfile.decode(block,new int[]{200},warm,null,0,600,1,Map.of()).get("infrared_temperature").quality()).isEqualTo("WARMUP_UNKNOWN");
        assertThat(SensorProfile.decode(block,new int[]{200},warm-1,power,0,600,1,Map.of()).get("infrared_temperature").quality()).isEqualTo("WARMING_UP");
        var valid=SensorProfile.decode(block,new int[]{200},warm,power,0,600,1,Map.of()).get("infrared_temperature");
        assertThat(valid.quality()).isEqualTo("GOOD");assertThat(valid.value()).isEqualTo(20.0);
        assertThat(SensorProfile.decode(block,new int[]{200},warm,power,100,600,1,Map.of()).get("infrared_temperature").quality()).isEqualTo("OUT_OF_RANGE");
    }
    @Test void unchangedNoiseIsValidAndFailuresDoNotManufactureZero() {
        var block=SensorProfile.plan(0).get(2);
        for(long time:new long[]{1000,9000}) {
            var noise=SensorProfile.decode(block,new int[]{713},time,null,0,600,1,Map.of()).get("noise");
            assertThat(noise.value()).isEqualTo(71.3);assertThat(noise.quality()).isEqualTo("GOOD");assertThat(noise.receivedAt()).isEqualTo(time);
        }
        var failed=SensorProfile.failure(block,"TIMEOUT",10000,1,Map.of()).get("noise");
        assertThat(failed.raw()).isNull();assertThat(failed.value()).isNull();assertThat(failed.quality()).isEqualTo("TIMEOUT");
    }
}
