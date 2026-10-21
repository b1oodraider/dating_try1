package com.dating.chat.application;

import com.dating.safety.application.BotRules;
import com.dating.safety.application.RestrictionGuard;
import com.dating.safety.domain.RestrictionKind;
import com.dating.chat.api.MessageDto;
import com.dating.chat.api.MessagePageDto;
import com.dating.chat.api.ReadReceiptDto;
import com.dating.chat.api.ReadStateDto;
import com.dating.chat.api.SendMessageRequest;
import com.dating.chat.domain.ChatMessage;
import com.dating.chat.domain.MessageType;
import com.dating.chat.persistence.ChatRepository;
import com.dating.chat.realtime.SseEmitterRegistry;
import com.dating.common.errors.ApiException;
import com.dating.common.ratelimit.FixedWindowRateLimiter;
import com.dating.economy.application.EconomyService;
import com.dating.economy.domain.CatalogItem;
import com.dating.productevents.application.ProductEventPublisher;
import com.dating.social.domain.MatchInfo;
import com.dating.social.application.MatchService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Messages exist only inside an active match, between its two participants. Sending is idempotent per
 * (match, sender, clientMessageId); new messages are pushed to both participants after commit.
 */
@Service
public class ChatService {

    public static final int MAX_TEXT_LENGTH = 2000;
    public static final int DEFAULT_PAGE_SIZE = 30;
    public static final int MAX_PAGE_SIZE = 100;
    static final String STICKER_PACK_CATEGORY = "STICKER_PACK";

    private final ChatRepository repository;
    private final MatchService matchService;
    private final EconomyService economyService;
    private final SseEmitterRegistry realtime;
    private final ApplicationEventPublisher eventPublisher;
    private final ProductEventPublisher productEvents;
    private final FixedWindowRateLimiter limiter;
    private final RestrictionGuard restrictionGuard;
    private final BotRules botRules;

    public ChatService(ChatRepository repository, MatchService matchService, EconomyService economyService,
                       SseEmitterRegistry realtime, ApplicationEventPublisher eventPublisher,
                       ProductEventPublisher productEvents, ChatProperties properties,
                       RestrictionGuard restrictionGuard, BotRules botRules) {
        this.restrictionGuard = restrictionGuard;
        this.botRules = botRules;
        this.repository = repository;
        this.matchService = matchService;
        this.economyService = economyService;
        this.realtime = realtime;
        this.eventPublisher = eventPublisher;
        this.productEvents = productEvents;
        this.limiter = new FixedWindowRateLimiter(properties.messagesPerMinute(), Duration.ofMinutes(1));
    }

    /**
     * @return the stored message as seen by the sender (the original one for a retry)
     * @throws ApiException 404 MATCH_NOT_FOUND (not my match, unmatched or blocked), 400 VALIDATION_ERROR,
     *                      sticker errors (404 ITEM_NOT_FOUND, 400 NOT_A_STICKER_PACK / UNKNOWN_STICKER,
     *                      403 ITEM_NOT_OWNED), 429 RATE_LIMITED
     */
    @Transactional
    public MessageDto send(UUID sender, UUID matchId, SendMessageRequest request) {
        restrictionGuard.requireAllowed(sender, RestrictionKind.MESSAGES);
        Content content = validate(request);
        MatchInfo match = matchService.requireActive(matchId, sender);

        var existing = repository.findByClientId(matchId, sender, request.clientMessageId());
        if (existing.isPresent()) {
            return MessageDto.of(existing.get(), sender);
        }
        long wait = limiter.tryAcquire(sender.toString());
        if (wait > 0) {
            throw ApiException.tooManyRequests("Слишком много сообщений. Подождите немного", wait);
        }
        if (content.type() == MessageType.STICKER) {
            requireOwnedSticker(sender, content.stickerPackCode(), content.sticker());
        }

        boolean inserted = repository.insert(UUID.randomUUID(), matchId, sender, request.clientMessageId(),
                content.type(), content.text(), content.stickerPackCode(), content.sticker());
        ChatMessage message = repository.findByClientId(matchId, sender, request.clientMessageId())
                .orElseThrow(() -> new IllegalStateException("Message must exist after insert"));
        if (inserted) {
            UUID recipient = match.other(sender);
            realtime.sendAfterCommit(sender, RealtimeEvents.MESSAGE, MessageDto.of(message, sender));
            realtime.sendAfterCommit(recipient, RealtimeEvents.MESSAGE, MessageDto.of(message, recipient));
            eventPublisher.publishEvent(new MessageSentEvent(matchId, message.id(), sender, recipient));
            if (content.type() == MessageType.TEXT) {
                botRules.afterTextMessage(sender, content.text());
            }
            productEvents.publish("chat_message_sent", Map.of("type", content.type().name()));
        }
        return MessageDto.of(message, sender);
    }

    /**
     * Newest first; {@code before} is the id of the oldest message the client already has.
     */
    @Transactional(readOnly = true)
    public MessagePageDto messages(UUID me, UUID matchId, UUID before, Integer limit) {
        matchService.requireActive(matchId, me);
        int size = limit == null || limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Long beforeSeq = before == null ? null : requireMessage(matchId, before).seq();
        List<ChatMessage> rows = repository.page(matchId, beforeSeq, size + 1);
        boolean hasMore = rows.size() > size;
        List<ChatMessage> page = hasMore ? rows.subList(0, size) : rows;
        List<MessageDto> items = page.stream().map(m -> MessageDto.of(m, me)).toList();
        return new MessagePageDto(items, hasMore ? page.getLast().id() : null);
    }

    /** «Печатает…»: tells the other person right away; nothing is stored. */
    @Transactional(readOnly = true)
    public void typing(UUID me, UUID matchId) {
        MatchInfo match = matchService.requireActive(matchId, me);
        realtime.send(match.other(me), RealtimeEvents.TYPING, Map.of("matchId", matchId));
    }

    /**
     * Moves my read pointer forward (never back) and sends a read receipt to the other person.
     */
    @Transactional
    public ReadStateDto markRead(UUID me, UUID matchId, UUID lastReadMessageId) {
        MatchInfo match = matchService.requireActive(matchId, me);
        ChatMessage message = requireMessage(matchId, lastReadMessageId);
        boolean advanced = repository.advanceRead(matchId, me, message.seq(), message.id());
        ChatRepository.ReadState state = repository.readStates(List.of(matchId)).stream()
                .filter(s -> s.accountId().equals(me))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Read state must exist after advanceRead"));
        if (advanced) {
            realtime.sendAfterCommit(match.other(me), RealtimeEvents.READ,
                    new ReadReceiptDto(matchId, state.lastReadMessageId(), state.lastReadAt()));
        }
        long unread = repository.unreadCounts(me, List.of(matchId)).getOrDefault(matchId, 0L);
        return new ReadStateDto(matchId, state.lastReadMessageId(), state.lastReadAt(), unread);
    }

    private ChatMessage requireMessage(UUID matchId, UUID messageId) {
        return repository.findById(messageId)
                .filter(m -> m.matchId().equals(matchId))
                .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "Сообщение не найдено"));
    }

    private void requireOwnedSticker(UUID sender, String packCode, String sticker) {
        CatalogItem pack = economyService.requireItem(packCode);
        if (!STICKER_PACK_CATEGORY.equals(pack.category())) {
            throw ApiException.badRequest("NOT_A_STICKER_PACK", "Это не набор стикеров");
        }
        if (economyService.balance(sender, packCode) <= 0) {
            throw ApiException.forbidden("ITEM_NOT_OWNED", "У вас нет этого набора стикеров");
        }
        Object stickers = pack.attributes().get("stickers");
        if (!(stickers instanceof List<?> codes) || !codes.contains(sticker)) {
            throw ApiException.badRequest("UNKNOWN_STICKER", "Такого стикера нет в наборе");
        }
    }

    private record Content(MessageType type, String text, String stickerPackCode, String sticker) {
    }

    private static Content validate(SendMessageRequest request) {
        if (request.type() == MessageType.TEXT) {
            String text = request.text() == null ? "" : request.text().trim();
            if (text.isEmpty()) {
                throw invalid("text", "Сообщение не может быть пустым");
            }
            if (text.length() > MAX_TEXT_LENGTH) {
                throw invalid("text", "Сообщение не должно превышать " + MAX_TEXT_LENGTH + " символов");
            }
            if (request.stickerPackCode() != null || request.sticker() != null) {
                throw invalid("sticker", "Текстовое сообщение не может содержать стикер");
            }
            return new Content(MessageType.TEXT, text, null, null);
        }
        if (isBlank(request.stickerPackCode())) {
            throw invalid("stickerPackCode", "Укажите набор стикеров");
        }
        if (isBlank(request.sticker())) {
            throw invalid("sticker", "Укажите стикер");
        }
        if (request.text() != null && !request.text().isBlank()) {
            throw invalid("text", "Стикер отправляется без текста");
        }
        return new Content(MessageType.STICKER, null, request.stickerPackCode().trim(), request.sticker().trim());
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных", Map.of(field, message));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
