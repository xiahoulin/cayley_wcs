package com.cayleywcs.monitor;

import com.cayleywcs.auth.JwtService;
import com.cayleywcs.common.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 浏览器用 Authorization: Bearer 换取一次性 WS 握手票据。 */
@RestController
@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
public class MonitorWsTicketController {
    private final JwtService jwtService;
    private final MonitorWsTickets tickets;

    public MonitorWsTicketController(JwtService jwtService, MonitorWsTickets tickets) {
        this.jwtService = jwtService;
        this.tickets = tickets;
    }

    @PostMapping("/monitor/ws-ticket")
    public ApiResponse<MonitorWsTickets.Ticket> issue(@RequestHeader("Authorization") String authorization) {
        if (!authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        JwtService.TokenSession session = jwtService.parseSession(authorization.substring(7))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return ApiResponse.success(tickets.issue(session.expiresAtMillis()));
    }
}
