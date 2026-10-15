package com.dating.social.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.social.domain.MatchInfo;
import com.dating.social.persistence.SocialRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Account deletion: the other side of every active match is told the chat is gone (MatchClosed → real-time
 * event after commit), then reactions, blocks, matches with their messages and the reports made by the
 * account are deleted in the same transaction as the account.
 */
@Component
public class SocialAccountCleanup {

    private final SocialRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public SocialAccountCleanup(SocialRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        UUID accountId = event.accountId();
        for (MatchInfo match : repository.activeMatchesInvolving(accountId)) {
            eventPublisher.publishEvent(new SocialEvents.MatchClosed(match.id(), match.accountLow(), match.accountHigh()));
        }
        repository.deleteAccountData(accountId);
    }
}
