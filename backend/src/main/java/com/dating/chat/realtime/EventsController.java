package com.dating.chat.realtime;

import com.dating.common.security.AuthenticatedUser;
import com.dating.common.security.JsonSecurityErrorHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Real-time events (Server-Sent Events). GET /api/v1/events is permitted in SecurityConfig and authenticated
 * here: either the usual Authorization header (mobile, fetch-based clients) or {@code ?ticket=} from
 * POST /api/v1/events/ticket (browser EventSource, which cannot set headers). The long-lived session token
 * is deliberately NOT accepted in the URL.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventsController {

    private final SseEmitterRegistry registry;
    private final EventTicketService tickets;
    private final JsonSecurityErrorHandler securityErrorHandler;

    public EventsController(SseEmitterRegistry registry, EventTicketService tickets,
                            JsonSecurityErrorHandler securityErrorHandler) {
        this.registry = registry;
        this.tickets = tickets;
        this.securityErrorHandler = securityErrorHandler;
    }

    @PostMapping("/ticket")
    public EventTicketService.Ticket ticket(@AuthenticationPrincipal AuthenticatedUser user) {
        return tickets.issue(user.accountId());
    }

    /**
     * The 401 is written directly (same JSON body as the security layer) because the request may accept
     * only text/event-stream, which the regular JSON error handler could not produce.
     */
    @GetMapping
    public SseEmitter stream(@AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(required = false) String ticket,
                             HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<UUID> accountId = user != null ? Optional.of(user.accountId()) : tickets.consume(ticket);
        if (accountId.isEmpty()) {
            securityErrorHandler.commence(request, response,
                    new InsufficientAuthenticationException("A session token or a valid event ticket is required"));
            return null;
        }
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no"); // nginx: do not buffer the stream
        return registry.register(accountId.get());
    }
}
