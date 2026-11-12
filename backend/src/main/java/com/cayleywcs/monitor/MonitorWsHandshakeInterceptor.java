package com.cayleywcs.monitor;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** /ws/monitor 在 Security 过滤器中放行 GET，仅此拦截器能放行实际升级。 */
@Component
public class MonitorWsHandshakeInterceptor implements HandshakeInterceptor {
    public static final String AUTHORIZED_UNTIL = "monitorAuthorizedUntil";
    private final MonitorWsTickets tickets;

    public MonitorWsHandshakeInterceptor(MonitorWsTickets tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("ticket");
        Long authorizedUntil = tickets.consume(ticket);
        if (authorizedUntil == null) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(AUTHORIZED_UNTIL, authorizedUntil);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
