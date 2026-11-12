package com.cayleywcs.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 管理端分页/统计的完整 HTTP 耗时；标签由固定路由表给出，不读业务入参。 */
@Component
public class WcsRequestMetricsFilter extends OncePerRequestFilter {
    private static final Map<String, String> OPERATIONS = Map.of(
            "/task/list", "task-page",
            "/alarm/list", "alarm-page",
            "/chain/list", "chain-page",
            "/stats/task-summary", "task-summary",
            "/stats/device-utilization", "device-utilization",
            "/stats/alarm-top", "alarm-top");
    private final MeterRegistry registry;

    public WcsRequestMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String operation = "POST".equals(request.getMethod()) ? OPERATIONS.get(request.getServletPath()) : null;
        if (operation == null) {
            chain.doFilter(request, response);
            return;
        }
        Timer.Sample sample = Timer.start(registry);
        try {
            chain.doFilter(request, response);
        } finally {
            sample.stop(registry.timer("wcs.query.http.duration", "operation", operation));
        }
    }
}
