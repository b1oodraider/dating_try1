package com.dating.chat.domain;

/**
 * TEXT: 1..2000 characters after trimming. STICKER: a sticker from a pack the sender owns.
 */
public enum MessageType {
    TEXT,
    STICKER,
    /** Posted by the server when a date plan is proposed, edited, accepted, declined or cancelled. */
    DATE_PLAN
}
