package com.dating.safety.application;

import com.dating.safety.domain.RestrictionKind;
import com.dating.safety.persistence.RiskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The one bot rule that acts by itself, because it is unambiguous: the same text with links or contacts sent to
 * {@value #SAME_TEXT_CHATS}+ different chats within a day. Messages are restricted for a day and the account goes
 * up the moderators' risk queue; everything else about bots only informs the risk score.
 */
@Component
public class BotRules {

    public static final String BOT_MESSAGES = "BOT_MESSAGES";
    static final int SAME_TEXT_CHATS = 3;
    static final int RESTRICTION_HOURS = 24;
    private static final Logger log = LoggerFactory.getLogger(BotRules.class);

    private final RiskRepository riskRepository;
    private final RiskService riskService;
    private final SanctionService sanctionService;
    private final RestrictionGuard guard;

    public BotRules(RiskRepository riskRepository, RiskService riskService, SanctionService sanctionService,
                    RestrictionGuard guard) {
        this.riskRepository = riskRepository;
        this.riskService = riskService;
        this.sanctionService = sanctionService;
        this.guard = guard;
    }

    /** Called after a text message is stored, in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void afterTextMessage(UUID sender, String text) {
        if (text == null || !RiskRepository.CONTACTS_JAVA.matcher(text).find()) {
            return;
        }
        int chats = riskRepository.chatsWithText(sender, text, Instant.now().minus(Duration.ofDays(1)));
        if (chats < SAME_TEXT_CHATS || guard.isRestricted(sender, RestrictionKind.MESSAGES)) {
            return;
        }
        sanctionService.restrict(sender, RestrictionKind.MESSAGES, RESTRICTION_HOURS, BOT_MESSAGES, "RULE", null,
                "одинаковый текст с контактами в " + chats + " чатах");
        riskService.recompute(sender);
        log.info("Bot messages from {}: messages restricted for {} h", sender, RESTRICTION_HOURS);
    }
}
