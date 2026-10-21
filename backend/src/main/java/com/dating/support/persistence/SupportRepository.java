package com.dating.support.persistence;

import com.dating.support.domain.SupportCategory;
import com.dating.support.domain.TicketStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SupportRepository {

    public record Ticket(UUID id, UUID accountId, SupportCategory category, TicketStatus status, Instant createdAt,
                         Instant updatedAt, Instant lastMessageAt) {
    }

    public record Message(UUID id, boolean fromStaff, String text, Instant createdAt) {
    }

    /** A queue row: the ticket, who wrote it and the start of the first message. */
    public record QueueRow(Ticket ticket, UUID profileId, String displayName, boolean testAccount, String preview) {
    }

    /** The account behind a ticket as staff see it. */
    public record Owner(String phoneMasked, UUID profileId, String displayName, boolean testAccount) {
    }

    private static final String COLUMNS =
            "t.id, t.account_id, t.category, t.status, t.created_at, t.updated_at, t.last_message_at";

    private final JdbcClient jdbc;

    public SupportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes ticket creation per account, so the daily limit holds under parallel requests. */
    public void lockAccount(UUID accountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('support:' || :account, 0))")
                .param("account", accountId.toString()).query((rs, n) -> 1).list();
    }

    public int countCreatedSince(UUID accountId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM support_tickets WHERE account_id = :account AND created_at > :since")
                .param("account", accountId).param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class).single();
    }

    public void createTicket(UUID id, UUID accountId, SupportCategory category) {
        jdbc.sql("INSERT INTO support_tickets (id, account_id, category, status) VALUES (:id, :account, :category, 'OPEN')")
                .param("id", id).param("account", accountId).param("category", category.name()).update();
    }

    /** Adds a message and moves the ticket to the given status. */
    public UUID addMessage(UUID ticketId, UUID authorAccountId, boolean fromStaff, String text, TicketStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO support_messages (id, ticket_id, author_account_id, from_staff, text)
                        VALUES (:id, :ticket, :author, :staff, :text)
                        """)
                .param("id", id).param("ticket", ticketId).param("author", authorAccountId)
                .param("staff", fromStaff).param("text", text).update();
        jdbc.sql("UPDATE support_tickets SET status = :status, updated_at = now(), last_message_at = now() WHERE id = :id")
                .param("status", status.name()).param("id", ticketId).update();
        return id;
    }

    public void setStatus(UUID ticketId, TicketStatus status) {
        jdbc.sql("UPDATE support_tickets SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", status.name()).param("id", ticketId).update();
    }

    public Optional<Ticket> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM support_tickets t WHERE t.id = :id")
                .param("id", id).query(SupportRepository::ticket).optional();
    }

    public Optional<Ticket> findForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM support_tickets t WHERE t.id = :id FOR UPDATE")
                .param("id", id).query(SupportRepository::ticket).optional();
    }

    /** Newest activity first. */
    public List<Ticket> ofAccount(UUID accountId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM support_tickets t WHERE t.account_id = :account"
                        + " ORDER BY t.last_message_at DESC, t.id")
                .param("account", accountId).query(SupportRepository::ticket).list();
    }

    public List<Message> messages(UUID ticketId) {
        return jdbc.sql("SELECT id, from_staff, text, created_at FROM support_messages WHERE ticket_id = :ticket"
                        + " ORDER BY created_at, id")
                .param("ticket", ticketId)
                .query((rs, n) -> new Message(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getString(3),
                        instant(rs, 4)))
                .list();
    }

    /** OPEN: the longest waiting first; ANSWERED and CLOSED: the latest activity first. */
    public List<QueueRow> queue(TicketStatus status, int limit, int offset) {
        String order = status == TicketStatus.OPEN ? "ASC" : "DESC";
        return jdbc.sql("SELECT " + COLUMNS + """
                        , p.id, p.display_name, a.test_account,
                          (SELECT left(m.text, 200) FROM support_messages m WHERE m.ticket_id = t.id
                            ORDER BY m.created_at, m.id LIMIT 1)
                        FROM support_tickets t
                        JOIN accounts a ON a.id = t.account_id
                        LEFT JOIN profiles p ON p.account_id = t.account_id
                        WHERE t.status = :status
                        ORDER BY t.last_message_at\s""" + order + ", t.id LIMIT :limit OFFSET :offset")
                .param("status", status.name()).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new QueueRow(ticket(rs, n), rs.getObject(8, UUID.class), rs.getString(9),
                        rs.getBoolean(10), rs.getString(11)))
                .list();
    }

    public long count(TicketStatus status) {
        return jdbc.sql("SELECT count(*) FROM support_tickets WHERE status = :status")
                .param("status", status.name()).query(Long.class).single();
    }

    public Owner owner(UUID accountId) {
        return jdbc.sql("""
                        SELECT a.phone_masked, p.id, p.display_name, a.test_account
                        FROM accounts a LEFT JOIN profiles p ON p.account_id = a.id
                        WHERE a.id = :account
                        """)
                .param("account", accountId)
                .query((rs, n) -> new Owner(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBoolean(4)))
                .single();
    }

    /** Messages go with their tickets (ON DELETE CASCADE). */
    public void deleteAccountData(UUID accountId) {
        jdbc.sql("DELETE FROM support_tickets WHERE account_id = :account").param("account", accountId).update();
    }

    private static Ticket ticket(ResultSet rs, int n) throws SQLException {
        return new Ticket(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                SupportCategory.valueOf(rs.getString(3)), TicketStatus.valueOf(rs.getString(4)),
                instant(rs, 5), instant(rs, 6), instant(rs, 7));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
