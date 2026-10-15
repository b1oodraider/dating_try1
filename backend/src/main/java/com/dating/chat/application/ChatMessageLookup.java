package com.dating.chat.application;

import com.dating.chat.persistence.ChatRepository;
import com.dating.social.application.MessageLookup;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Lets the social module validate and snapshot a reported message without depending on chat.
 */
@Component
public class ChatMessageLookup implements MessageLookup {

    private final ChatRepository repository;

    public ChatMessageLookup(ChatRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MessageRef> find(UUID messageId) {
        return repository.findById(messageId)
                .map(m -> new MessageRef(m.matchId(), m.senderAccountId(), m.snapshot()));
    }
}
