package com.dating.social.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Read access to chat messages for reports, implemented by the chat module.
 * Declared here so that social does not depend on chat (chat already depends on social).
 */
public interface MessageLookup {

    /**
     * @param snapshot human-readable content stored with the report (text, or a sticker description)
     */
    record MessageRef(UUID matchId, UUID senderAccountId, String snapshot) {
    }

    Optional<MessageRef> find(UUID messageId);
}
