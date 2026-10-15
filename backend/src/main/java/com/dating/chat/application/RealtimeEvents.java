package com.dating.chat.application;

/**
 * Names of the SSE events (GET /api/v1/events). Besides these the stream sends {@code ready} on connect and
 * {@code heartbeat} periodically.
 */
public final class RealtimeEvents {

    /** Payload: Message (as seen by the recipient; also sent to the sender's other connections). */
    public static final String MESSAGE = "message";
    /** Payload: Match (a new match, as seen by the recipient). */
    public static final String MATCH = "match";
    /** Payload: { matchId } — the match was unmatched / blocked / the other account was deleted. */
    public static final String MATCH_CLOSED = "match_closed";
    /** Payload: { superlike, count } — someone liked me; no identity (who liked me may be premium). */
    public static final String LIKE_RECEIVED = "like_received";
    /** Payload: ReadReceipt — the other person read the chat up to a message. */
    public static final String READ = "read";

    private RealtimeEvents() {
    }
}
