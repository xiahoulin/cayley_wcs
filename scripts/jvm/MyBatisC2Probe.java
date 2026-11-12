package org.apache.ibatis.scripting.xmltags;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;

/** Exercises the packaged MyBatis implementation; this is not a crash reproducer. */
public class MyBatisC2Probe {
    static volatile long checksum;

    public static void main(String[] args) {
        var vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        for (var entry : Map.of("TieredCompilation", "true", "TieredStopAtLevel", "4", "UseCompiler", "true").entrySet()) {
            if (!entry.getValue().equals(vm.getVMOption(entry.getKey()).getValue())) {
                throw new AssertionError("Default C2 compilation is required: " + entry.getKey());
            }
        }
        var local = new DynamicContext.ContextMap(null, false);
        local.put("present", 17);
        local.put(null, 29);
        var backing = new HashMap<String, Object>();
        backing.put("appId", 23);
        var mapped = new DynamicContext.ContextMap(SystemMetaObject.forObject(backing), false);
        var fallback = new DynamicContext.ContextMap(SystemMetaObject.forObject("parameter"), true);
        long sum = 0;
        for (int phase = 0; phase < 8; phase++) {
            for (int i = 0; i < 2_000_000; i++) {
                sum += (Integer) local.get("present");
                require(local.get("missing") == null);
                require(Objects.equals(mapped.get("appId"), 23));
                require(Objects.equals(fallback.get("unknown"), "parameter"));
                if ((i & 1023) == 0) require(Objects.equals(local.get(null), 29));
            }
            checksum = sum;
        }
        require(sum == 272_000_000L);
        var source = new XMLLanguageDriver().createSqlSource(new Configuration(),
                "<script>SELECT id FROM wcs_application WHERE 1=1"
                + "<if test='id != null'> AND id=#{id}</if>"
                + "<if test='enabled != null'> AND enabled=#{enabled}</if></script>", Map.class);
        for (int i = 0; i < 200_000; i++) {
            Map<String, Object> parameters = new HashMap<>();
            if ((i & 1) != 0) parameters.put("id", 23L);
            if ((i & 2) != 0) parameters.put("enabled", true);
            var bound = source.getBoundSql(parameters);
            require(bound.getParameterMappings().size() == parameters.size());
            require(bound.getSql().contains("AND id=") == parameters.containsKey("id"));
            require(bound.getSql().contains("AND enabled=") == parameters.containsKey("enabled"));
        }
        System.out.println("MYBATIS_C2_PROBE_OK checksum=" + sum + " dynamicSqlCases=200000 java=" + Runtime.version());
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("MyBatis parameter/SQL result mismatch");
    }
}
