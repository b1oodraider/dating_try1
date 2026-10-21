package com.dating.chat.api;

import com.dating.profiles.api.PublicProfileDto;

import java.time.Instant;
import java.util.UUID;

/**
 * A match with its chat summary, as seen by one participant.
 *
 * @param profile                the other person
 * @param lastMessage            newest message (preview), null if the chat is empty
 * @param unreadCount            messages from the other person after my read pointer
 * @param myLastReadMessageId    my read pointer
 * @param otherLastReadMessageId read receipt: the other person's read pointer
 * @param otherLastReadAt        read receipt: when the other person last moved it
 */
public record MatchDto(
        UUID id,
        Instant createdAt,
        PublicProfileDto profile,
        MessageDto lastMessage,
        long unreadCount,
        UUID myLastReadMessageId,
        UUID otherLastReadMessageId,
        Instant otherLastReadAt,
        /** When the other person was last active; null = not known yet. */
        Instant otherLastSeenAt,
        /** The other person has the app open (event stream) or was active in the last 2 minutes. */
        boolean otherOnline
) {
}
