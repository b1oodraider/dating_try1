package com.dating.support.api;

import com.dating.common.security.AuthenticatedUser;
import com.dating.support.application.SupportService;
import com.dating.support.domain.SupportCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Support tickets: the user's side (/support/tickets) and the staff queue (/admin/support/tickets,
 * SUPPORT, MODERATOR or ADMIN, else 403 ROLE_REQUIRED). See docs/support.md.
 */
@RestController
@RequestMapping("/api/v1")
public class SupportController {

    public record CreateTicketRequest(
            @NotNull(message = "Выберите тему обращения") SupportCategory category,
            @NotBlank(message = "Опишите проблему")
            @Size(max = 4000, message = "Сообщение не должно превышать 4000 символов") String text
    ) {
    }

    public record MessageRequest(
            @NotBlank(message = "Напишите сообщение")
            @Size(max = 4000, message = "Сообщение не должно превышать 4000 символов") String text
    ) {
    }

    private final SupportService service;

    public SupportController(SupportService service) {
        this.service = service;
    }

    @PostMapping("/support/tickets")
    public ResponseEntity<SupportService.TicketView> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @Valid @RequestBody CreateTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(user.accountId(), request.category(), request.text()));
    }

    @GetMapping("/support/tickets")
    public List<SupportService.TicketSummary> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.mine(user.accountId());
    }

    @GetMapping("/support/tickets/{ticketId}")
    public SupportService.TicketView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID ticketId) {
        return service.get(user.accountId(), ticketId);
    }

    @PostMapping("/support/tickets/{ticketId}/messages")
    public SupportService.TicketView write(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID ticketId,
                                           @Valid @RequestBody MessageRequest request) {
        return service.write(user.accountId(), ticketId, request.text());
    }

    @GetMapping("/admin/support/tickets")
    public SupportService.StaffQueuePage queue(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int limit) {
        return service.queue(user.accountId(), status, page, limit);
    }

    @GetMapping("/admin/support/tickets/{ticketId}")
    public SupportService.StaffTicketView staffGet(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @PathVariable UUID ticketId) {
        return service.staffGet(user.accountId(), ticketId);
    }

    @PostMapping("/admin/support/tickets/{ticketId}/messages")
    public SupportService.StaffTicketView reply(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable UUID ticketId,
                                                @Valid @RequestBody MessageRequest request) {
        return service.reply(user.accountId(), ticketId, request.text());
    }

    @PostMapping("/admin/support/tickets/{ticketId}/close")
    public SupportService.StaffTicketView close(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable UUID ticketId) {
        return service.close(user.accountId(), ticketId);
    }
}
