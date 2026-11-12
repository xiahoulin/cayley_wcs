package com.cayleywcs.monitor;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 管理端 JWT 换取单次、短期 WS 握手票据；票据不等于 JWT，也不授予其他接口权限。 */
@Component
public class MonitorWsTickets {
    private static final long TICKET_TTL_MS = 30_000;
    private static final int MAX_PENDING = 2_048;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    public Ticket issue(long jwtExpiresAtMillis) {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> entry.getValue().ticketExpiresAtMillis <= now);
        if (jwtExpiresAtMillis <= now || pending.size() >= MAX_PENDING) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "WS 票据暂不可用");
        }
        long ticketExpiresAtMillis = Math.min(now + TICKET_TTL_MS, jwtExpiresAtMillis);
        String value;
        do {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (pending.putIfAbsent(value, new Pending(ticketExpiresAtMillis, jwtExpiresAtMillis)) != null);
        return new Ticket(value, ticketExpiresAtMillis);
    }

    public Long consume(String value) {
        if (value == null || value.length() != 43 || !value.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        Pending ticket = pending.remove(value);
        long now = System.currentTimeMillis();
        return ticket != null && ticket.ticketExpiresAtMillis > now && ticket.jwtExpiresAtMillis > now
                ? ticket.jwtExpiresAtMillis : null;
    }

    public record Ticket(String ticket, long expiresAtMillis) {
    }

    private record Pending(long ticketExpiresAtMillis, long jwtExpiresAtMillis) {
    }
}
