package com.dating.chat.persistence;

import com.dating.chat.domain.ChatMessage;
import com.dating.chat.domain.MessageType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Messages and read state. Plain SQL: idempotent insert by client message id and a monotonic read pointer
 * are single statements. Rows disappear with their match (ON DELETE CASCADE).
 */
@Repository
public class ChatRepository {

    public record ReadState(UUID matchId, UUID accountId, long lastReadSeq, UUID lastReadMessageId, Instant lastReadAt) {
    }

    private static final String COLUMNS = """
            c.id, c.seq, c.match_id, c.sender_account_id, c.client_message_id, c.type, c.text,
            c.sticker_pack_code, c.sticker, c.created_at, c.date_plan_id,
            r.id AS reply_id, r.sender_account_id AS reply_sender, r.type AS reply_type, r.text AS reply_text""";
    /** Messages with the one each answers. */
    private static final String FROM = " FROM chat_messages c LEFT JOIN chat_messages r ON r.id = c.reply_to_message_id ";

    private final JdbcClient jdbc;

    public ChatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return false if this sender already sent a message with this client id in the match (a retry)
     */
    public boolean insert(UUID id, UUID matchId, UUID sender, String clientMessageId, MessageType type,
                          String text, String stickerPackCode, String sticker) {
        return insert(id, matchId, sender, clientMessageId, type, text, stickerPackCode, sticker, null, null);
    }

    public boolean insert(UUID id, UUID matchId, UUID sender, String clientMessageId, MessageType type,
                          String text, String stickerPackCode, String sticker, UUID datePlanId, UUID replyTo) {
        return jdbc.sql("""
                        INSERT INTO chat_messages (id, match_id, sender_account_id, client_message_id, type, text,
                                                   sticker_pack_code, sticker, date_plan_id, reply_to_message_id)
                        VALUES (:id, :matchId, :sender, :clientId, :type, :text, :pack, :sticker, :plan, :reply)
                        ON CONFLICT ON CONSTRAINT uq_chat_messages_client_id DO NOTHING
                        """)
                .param("id", id)
                .param("matchId", matchId)
                .param("sender", sender)
                .param("clientId", clientMessageId)
                .param("type", type.name())
                .param("text", text)
                .param("pack", stickerPackCode)
                .param("sticker", sticker)
                .param("plan", datePlanId)
                .param("reply", replyTo)
                .update() == 1;
    }

    public Optional<ChatMessage> findByClientId(UUID matchId, UUID sender, String clientMessageId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + """
                        WHERE c.match_id = :matchId AND c.sender_account_id = :sender AND c.client_message_id = :clientId
                        """)
                .param("matchId", matchId)
                .param("sender", sender)
                .param("clientId", clientMessageId)
                .query(ChatRepository::message)
                .optional();
    }

    public Optional<ChatMessage> findById(UUID messageId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.id = :id")
                .param("id", messageId)
                .query(ChatRepository::message)
                .optional();
    }

    /**
     * Newest first. {@code beforeSeq} null = from the newest message.
     */
    public List<ChatMessage> page(UUID matchId, Long beforeSeq, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + "WHERE c.match_id = :matchId"
                        + (beforeSeq == null ? "" : " AND c.seq < :before")
                        + " ORDER BY c.seq DESC LIMIT :limit")
                .param("matchId", matchId)
                .param("before", beforeSeq == null ? 0L : beforeSeq)
                .param("limit", limit)
                .query(ChatRepository::message)
                .list();
    }

    public Map<UUID, ChatMessage> lastMessages(Collection<UUID> matchIds) {
        Map<UUID, ChatMessage> result = new HashMap<>();
        if (matchIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT DISTINCT ON (c.match_id) " + COLUMNS + FROM + """
                        WHERE c.match_id IN (:ids)
                        ORDER BY c.match_id, c.seq DESC
                        """)
                .param("ids", matchIds)
                .query(ChatRepository::message)
                .list()
                .forEach(m -> result.put(m.matchId(), m));
        return result;
    }

    /**
     * Messages from the other side after my read pointer, per match.
     */
    public Map<UUID, Long> unreadCounts(UUID me, Collection<UUID> matchIds) {
        Map<UUID, Long> result = new HashMap<>();
        if (matchIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        SELECT c.match_id, count(*)
                        FROM chat_messages c
                        LEFT JOIN chat_read_state rs ON rs.match_id = c.match_id AND rs.account_id = :me
                        WHERE c.match_id IN (:ids) AND c.sender_account_id <> :me
                          AND c.seq > COALESCE(rs.last_read_seq, 0)
                        GROUP BY c.match_id
                        """)
                .param("me", me)
                .param("ids", matchIds)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getLong(2)))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    public List<ReadState> readStates(Collection<UUID> matchIds) {
        if (matchIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT match_id, account_id, last_read_seq, last_read_message_id, last_read_at
                        FROM chat_read_state WHERE match_id IN (:ids)
                        """)
                .param("ids", matchIds)
                .query((rs, n) -> new ReadState(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                        rs.getObject(4, UUID.class), instant(rs, 5)))
                .list();
    }

    /**
     * Moves the read pointer forward only (an older id never moves it back).
     *
     * @return true if the pointer moved
     */
    public boolean advanceRead(UUID matchId, UUID accountId, long seq, UUID messageId) {
        return jdbc.sql("""
                        INSERT INTO chat_read_state (match_id, account_id, last_read_seq, last_read_message_id, last_read_at)
                        VALUES (:matchId, :accountId, :seq, :messageId, now())
                        ON CONFLICT (match_id, account_id) DO UPDATE
                        SET last_read_seq = EXCLUDED.last_read_seq, last_read_message_id = EXCLUDED.last_read_message_id,
                            last_read_at = EXCLUDED.last_read_at
                        WHERE chat_read_state.last_read_seq < EXCLUDED.last_read_seq
                        """)
                .param("matchId", matchId)
                .param("accountId", accountId)
                .param("seq", seq)
                .param("messageId", messageId)
                .update() == 1;
    }

    private static ChatMessage message(ResultSet rs, int rowNum) throws SQLException {
        return new ChatMessage(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getObject("match_id", UUID.class),
                rs.getObject("sender_account_id", UUID.class),
                rs.getString("client_message_id"),
                MessageType.valueOf(rs.getString("type")),
                rs.getString("text"),
                rs.getString("sticker_pack_code"),
                rs.getString("sticker"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("date_plan_id", UUID.class),
                rs.getObject("reply_id", UUID.class) == null ? null : new ChatMessage.Reply(
                        rs.getObject("reply_id", UUID.class),
                        rs.getObject("reply_sender", UUID.class),
                        MessageType.valueOf(rs.getString("reply_type")),
                        rs.getString("reply_text")));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
