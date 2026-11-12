package com.cayleywcs.telemetry.profile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Register offsets are wire zero-based; samples are receipt times, not device sample times. */
public final class SensorProfile {
    public static final String VERSION = "three-sensors-v1";
    public static final long STALE_MS = 6000, WARMUP_MS = 40 * 60 * 1000;
    public record Block(String name, int unit, int offset, int count, List<String> metrics) { }
    public record Measurement(String metric, Integer raw, Double value, String unit, String quality,
                              long receivedAt, String profileVersion, Map<String,Object> context) { }
    public static List<Block> plan(int infraredOffset) {
        if (infraredOffset != 0 && infraredOffset != 1) throw new IllegalArgumentException("IR offset must be 0 or 1");
        return List.of(new Block("vibration_speed",80,0x3a,3,List.of("vibration_speed_x","vibration_speed_y","vibration_speed_z")),
                new Block("vibration_detail",80,0x40,7,List.of("vibration_chip_temperature","vibration_displacement_x",
                        "vibration_displacement_y","vibration_displacement_z","vibration_frequency_x","vibration_frequency_y","vibration_frequency_z")),
                new Block("noise",1,0,1,List.of("noise")), new Block("infrared",2,infraredOffset,1,List.of("infrared_temperature")));
    }
    public static Map<String,Measurement> decode(Block block, int[] raw, long receivedAt, Long powerOnAt,
            double infraredMin, double infraredMax, long revision, Map<String,Object> context) {
        if (raw.length != block.count()) throw new IllegalArgumentException("register count mismatch");
        Map<String,Measurement> result = new LinkedHashMap<>();
        for (int i = 0; i < raw.length; i++) {
            String metric = block.metrics().get(i), quality = "GOOD";
            if (raw[i] < 0 || raw[i] > 65535) throw new IllegalArgumentException("not UINT16");
            double divisor = metric.startsWith("vibration_speed") || metric.equals("vibration_chip_temperature") ? 100
                    : metric.startsWith("vibration_displacement") ? 1 : 10;
            Double value = raw[i] / divisor;
            if (metric.equals("vibration_chip_temperature") && raw[i] >= 32768) { quality = "UNKNOWN"; value = null; }
            if (metric.equals("noise") && (value < 30 || value > 130)) quality = "OUT_OF_RANGE";
            if (metric.equals("infrared_temperature")) {
                if (powerOnAt == null || powerOnAt > receivedAt) quality = "WARMUP_UNKNOWN";
                else if (receivedAt - powerOnAt < WARMUP_MS) quality = "WARMING_UP";
                else if (value < infraredMin || value > infraredMax) quality = "OUT_OF_RANGE";
            }
            result.put(metric, new Measurement(metric, raw[i], value, unit(metric), quality, receivedAt,
                    VERSION + "/" + revision, contextAtReceipt(context, receivedAt)));
        }
        return result;
    }
    public static Map<String,Measurement> failure(Block block, String quality, long receivedAt, long revision, Map<String,Object> context) {
        Map<String,Measurement> result = new LinkedHashMap<>();
        for (String metric : block.metrics()) result.put(metric, new Measurement(metric,null,null,unit(metric),quality,
                receivedAt,VERSION + "/" + revision,contextAtReceipt(context, receivedAt)));
        return result;
    }
    public static String unit(String metric) {
        if (metric.startsWith("vibration_speed")) return "mm/s";
        if (metric.startsWith("vibration_displacement")) return "μm";
        if (metric.startsWith("vibration_frequency")) return "Hz";
        if (metric.equals("noise")) return "dB";
        return "°C";
    }
    private static Map<String,Object> contextAtReceipt(Map<String,Object> context,long receivedAt) {
        Map<String,Object> result=new LinkedHashMap<>(context);
        if(context.get("ageMs") instanceof Number age && context.get("capturedAt") instanceof Number captured) {
            long atReceipt=age.longValue()+Math.max(0,receivedAt-captured.longValue());
            result.put("ageMs",atReceipt);
            if(atReceipt>STALE_MS)result.put("quality","STALE");
        }
        return Map.copyOf(result);
    }
    private SensorProfile() { }
}
