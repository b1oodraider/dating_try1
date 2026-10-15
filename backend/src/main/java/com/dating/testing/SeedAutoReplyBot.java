package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.chat.api.SendMessageRequest;
import com.dating.chat.application.ChatService;
import com.dating.chat.application.MessageSentEvent;
import com.dating.chat.domain.MessageType;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * QA bot (app.testing.seed-auto-reply=true): when a person writes to a seed profile, the seed answers with a
 * canned phrase after app.testing.seed-auto-reply-delay. Scheduled after the sender's transaction commits,
 * on its own thread, so the request is never blocked and the reply never races the original message.
 * Seeds never answer seeds, so two bots cannot loop.
 */
@Component
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class SeedAutoReplyBot {

    private static final Logger log = LoggerFactory.getLogger(SeedAutoReplyBot.class);

    public static final List<String> PHRASES = List.of(
            "Привет! Приятно познакомиться 🙂",
            "Интересно, расскажи подробнее!",
            "Ха-ха, это точно 😄",
            "А как прошёл твой день?",
            "Звучит здорово! Я тоже такое люблю",
            "Хороший вопрос, дай подумать… А ты как считаешь?",
            "Полностью поддерживаю!",
            "Давай продолжим, мне нравится наш разговор");

    private final TestSocialProperties properties;
    private final AccountService accountService;
    private final ChatService chatService;
    private final ScheduledExecutorService executor;

    public SeedAutoReplyBot(TestSocialProperties properties, AccountService accountService, ChatService chatService) {
        this.properties = properties;
        this.accountService = accountService;
        this.chatService = chatService;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "seed-auto-reply");
            thread.setDaemon(true);
            return thread;
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageSent(MessageSentEvent event) {
        if (!properties.seedAutoReply()) {
            return;
        }
        executor.schedule(() -> reply(event), properties.seedAutoReplyDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void reply(MessageSentEvent event) {
        try {
            if (!accountService.isActiveSeedAccount(event.recipientAccountId())
                    || accountService.isActiveSeedAccount(event.senderAccountId())) {
                return;
            }
            String phrase = PHRASES.get(ThreadLocalRandom.current().nextInt(PHRASES.size()));
            chatService.send(event.recipientAccountId(), event.matchId(),
                    new SendMessageRequest("auto-reply:" + event.messageId(), MessageType.TEXT, phrase, null, null));
        } catch (RuntimeException e) {
            // e.g. the match was closed meanwhile or the seed hit the rate limit: a QA helper must not fail loudly
            log.info("Seed auto-reply skipped: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
