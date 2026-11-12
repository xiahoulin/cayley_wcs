package com.cayleywcs.config;

import com.cayleywcs.monitor.MonitorWebSocketHandler;
import com.cayleywcs.monitor.MonitorWsHandshakeInterceptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final MonitorWebSocketHandler monitorWebSocketHandler;
    private final MonitorWsHandshakeInterceptor handshakeInterceptor;
    private final List<String> allowedOriginPatterns;

    public WebSocketConfig(MonitorWebSocketHandler monitorWebSocketHandler,
                           MonitorWsHandshakeInterceptor handshakeInterceptor,
                           @Value("${cayleywcs.security.allowed-origin-patterns:"
                                   + SecurityConfig.DEFAULT_ALLOWED_ORIGIN_PATTERNS + "}") String allowedOriginPatterns) {
        this.monitorWebSocketHandler = monitorWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.allowedOriginPatterns = SecurityConfig.parseAllowedOriginPatterns(allowedOriginPatterns);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(monitorWebSocketHandler, "/ws/monitor")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginPatterns.toArray(String[]::new));
    }
}
