package com.dating.chat.application;

import com.dating.chat.realtime.SseEmitterRegistry;
import com.dating.social.application.ReceivedLikesService;
import com.dating.social.application.SocialEvents;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Turns social domain events into real-time events. Payloads are built inside the business transaction
 * (they see its data) and delivered only after it commits.
 */
@Component
public class RealtimeNotifier {

    private final SseEmitterRegistry realtime;
    private final MatchViews matchViews;
    private final ReceivedLikesService receivedLikes;

    public RealtimeNotifier(SseEmitterRegistry realtime, MatchViews matchViews, ReceivedLikesService receivedLikes) {
        this.realtime = realtime;
        this.matchViews = matchViews;
        this.receivedLikes = receivedLikes;
    }

    @EventListener
    public void onMatchCreated(SocialEvents.MatchCreated event) {
        for (UUID accountId : new UUID[]{event.accountA(), event.accountB()}) {
            matchViews.one(accountId, event.matchId())
                    .ifPresent(match -> realtime.sendAfterCommit(accountId, RealtimeEvents.MATCH, match));
        }
    }

    @EventListener
    public void onMatchClosed(SocialEvents.MatchClosed event) {
        Map<String, UUID> payload = Map.of("matchId", event.matchId());
        realtime.sendAfterCommit(event.accountA(), RealtimeEvents.MATCH_CLOSED, payload);
        realtime.sendAfterCommit(event.accountB(), RealtimeEvents.MATCH_CLOSED, payload);
    }

    @EventListener
    public void onLikeReceived(SocialEvents.LikeReceived event) {
        long count = receivedLikes.count(event.targetAccountId()).count();
        realtime.sendAfterCommit(event.targetAccountId(), RealtimeEvents.LIKE_RECEIVED,
                Map.of("superlike", event.superlike(), "count", count));
    }
}
