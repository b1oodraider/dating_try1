package com.dating.support.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.admin.application.AdminAuditLog;
import com.dating.common.errors.ApiException;
import com.dating.common.security.Role;
import com.dating.common.security.RoleAccess;
import com.dating.support.domain.SupportCategory;
import com.dating.support.domain.TicketStatus;
import com.dating.support.persistence.SupportRepository;
import com.dating.support.persistence.SupportRepository.Message;
import com.dating.support.persistence.SupportRepository.Ticket;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Technical support (docs/support.md): a user opens a ticket with a first message and writes in it; staff
 * (SUPPORT, MODERATOR or ADMIN) answer from the back office. A staff answer sets ANSWERED and sends a push
 * without the text; a user message sets OPEN again (also on a closed ticket).
 */
@Service
public class SupportService {

    /** What the user sees: no staff identities. */
    public record TicketView(UUID id, SupportCategory category, TicketStatus status, Instant createdAt,
                             Instant updatedAt, Instant lastMessageAt, List<Message> messages) {
    }

    public record TicketSummary(UUID id, SupportCategory category, TicketStatus status, Instant createdAt,
                                Instant updatedAt, Instant lastMessageAt) {
    }

    public record StaffQueueItem(UUID id, UUID accountId, UUID profileId, String displayName, boolean testAccount,
                                 SupportCategory category, TicketStatus status, Instant createdAt,
                                 Instant lastMessageAt, String preview) {
    }

    public record StaffQueuePage(List<StaffQueueItem> items, int page, int limit, long totalElements) {
    }

    public record StaffTicketView(UUID id, UUID accountId, String phoneMasked, UUID profileId, String displayName,
                                  boolean testAccount, SupportCategory category, TicketStatus status,
                                  Instant createdAt, Instant updatedAt, Instant lastMessageAt,
                                  List<Message> messages) {
    }

    /** Published on a staff answer; the push goes out after commit. */
    public record ReplyPosted(UUID accountId, UUID ticketId) {
    }

    static final int MAX_TICKETS_PER_DAY = 5;
    static final String ENTITY = "SUPPORT_TICKET";
    private static final int MAX_LIMIT = 100;
    private static final Role[] STAFF = {Role.SUPPORT, Role.MODERATOR, Role.ADMIN};

    private final SupportRepository repository;
    private final RoleAccess roleAccess;
    private final AdminAuditLog auditLog;
    private final ApplicationEventPublisher events;

    public SupportService(SupportRepository repository, RoleAccess roleAccess, AdminAuditLog auditLog,
                          ApplicationEventPublisher events) {
        this.repository = repository;
        this.roleAccess = roleAccess;
        this.auditLog = auditLog;
        this.events = events;
    }

    // ---------------------------------------------------------------- user

    /**
     * @throws ApiException 429 RATE_LIMITED (5 new tickets a day)
     */
    @Transactional
    public TicketView create(UUID accountId, SupportCategory category, String text) {
        repository.lockAccount(accountId);
        if (repository.countCreatedSince(accountId, Instant.now().minus(Duration.ofDays(1))) >= MAX_TICKETS_PER_DAY) {
            throw ApiException.tooManyRequests("Слишком много обращений. Попробуйте завтра или напишите в уже открытое",
                    Duration.ofHours(24).toSeconds());
        }
        UUID id = UUID.randomUUID();
        repository.createTicket(id, accountId, category);
        repository.addMessage(id, accountId, false, text.trim(), TicketStatus.OPEN);
        return view(repository.find(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<TicketSummary> mine(UUID accountId) {
        return repository.ofAccount(accountId).stream()
                .map(t -> new TicketSummary(t.id(), t.category(), t.status(), t.createdAt(), t.updatedAt(),
                        t.lastMessageAt()))
                .toList();
    }

    /** @throws ApiException 404 TICKET_NOT_FOUND (also for someone else's ticket) */
    @Transactional(readOnly = true)
    public TicketView get(UUID accountId, UUID ticketId) {
        return view(repository.find(ticketId).filter(t -> t.accountId().equals(accountId)).orElseThrow(SupportService::notFound));
    }

    /** @throws ApiException 404 TICKET_NOT_FOUND */
    @Transactional
    public TicketView write(UUID accountId, UUID ticketId, String text) {
        Ticket ticket = repository.findForUpdate(ticketId).filter(t -> t.accountId().equals(accountId))
                .orElseThrow(SupportService::notFound);
        repository.addMessage(ticket.id(), accountId, false, text.trim(), TicketStatus.OPEN);
        return view(repository.find(ticketId).orElseThrow());
    }

    // ---------------------------------------------------------------- staff

    /** @param status null = OPEN (the queue) */
    @Transactional(readOnly = true)
    public StaffQueuePage queue(UUID actor, String status, int page, int limit) {
        roleAccess.require(actor, STAFF);
        TicketStatus wanted = parseStatus(status);
        int safeLimit = limit <= 0 || limit > MAX_LIMIT ? 20 : limit;
        int safePage = Math.max(page, 0);
        List<StaffQueueItem> items = repository.queue(wanted, safeLimit, safePage * safeLimit).stream()
                .map(r -> new StaffQueueItem(r.ticket().id(), r.ticket().accountId(), r.profileId(), r.displayName(),
                        r.testAccount(), r.ticket().category(), r.ticket().status(), r.ticket().createdAt(),
                        r.ticket().lastMessageAt(), r.preview()))
                .toList();
        return new StaffQueuePage(items, safePage, safeLimit, repository.count(wanted));
    }

    @Transactional(readOnly = true)
    public StaffTicketView staffGet(UUID actor, UUID ticketId) {
        roleAccess.require(actor, STAFF);
        return staffView(repository.find(ticketId).orElseThrow(SupportService::notFound));
    }

    /** Works on any status; the ticket becomes ANSWERED. */
    @Transactional
    public StaffTicketView reply(UUID actor, UUID ticketId, String text) {
        roleAccess.require(actor, STAFF);
        Ticket ticket = repository.findForUpdate(ticketId).orElseThrow(SupportService::notFound);
        UUID messageId = repository.addMessage(ticketId, actor, true, text.trim(), TicketStatus.ANSWERED);
        auditLog.record(actor, "REPLY", ENTITY, ticketId.toString(), Map.of("status", ticket.status()),
                Map.of("status", TicketStatus.ANSWERED, "messageId", messageId));
        events.publishEvent(new ReplyPosted(ticket.accountId(), ticketId));
        return staffView(repository.find(ticketId).orElseThrow());
    }

    /** Idempotent: closing a closed ticket changes nothing and is not audited. */
    @Transactional
    public StaffTicketView close(UUID actor, UUID ticketId) {
        roleAccess.require(actor, STAFF);
        Ticket ticket = repository.findForUpdate(ticketId).orElseThrow(SupportService::notFound);
        if (ticket.status() != TicketStatus.CLOSED) {
            repository.setStatus(ticketId, TicketStatus.CLOSED);
            auditLog.record(actor, "CLOSE", ENTITY, ticketId.toString(), Map.of("status", ticket.status()),
                    Map.of("status", TicketStatus.CLOSED));
        }
        return staffView(repository.find(ticketId).orElseThrow());
    }

    // ---------------------------------------------------------------- lifecycle

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAccountData(event.accountId());
    }

    private TicketView view(Ticket t) {
        return new TicketView(t.id(), t.category(), t.status(), t.createdAt(), t.updatedAt(), t.lastMessageAt(),
                repository.messages(t.id()));
    }

    private StaffTicketView staffView(Ticket t) {
        SupportRepository.Owner owner = repository.owner(t.accountId());
        return new StaffTicketView(t.id(), t.accountId(), owner.phoneMasked(), owner.profileId(), owner.displayName(),
                owner.testAccount(), t.category(), t.status(), t.createdAt(), t.updatedAt(), t.lastMessageAt(),
                repository.messages(t.id()));
    }

    private static TicketStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return TicketStatus.OPEN;
        }
        try {
            return TicketStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("VALIDATION_ERROR", "Неизвестный статус",
                    Map.of("status", "Одно из: OPEN, ANSWERED, CLOSED"));
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("TICKET_NOT_FOUND", "Обращение не найдено");
    }
}
