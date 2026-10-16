package com.dating.chat.application;

import com.dating.chat.api.MatchDto;
import com.dating.chat.api.MessageDto;
import com.dating.chat.domain.ChatMessage;
import com.dating.chat.persistence.ChatRepository;
import com.dating.profiles.api.PublicProfileDto;
import com.dating.social.application.MatchService;
import com.dating.social.application.PublicProfileAssembler;
import com.dating.social.domain.MatchInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The match list ("chats" screen): the other person's public profile, the last message, unread count and
 * read receipts. Everything is loaded in a fixed number of queries regardless of the number of matches.
 */
@Service
public class MatchViews {

    private final MatchService matchService;
    private final ChatRepository repository;
    private final PublicProfileAssembler profiles;

    public MatchViews(MatchService matchService, ChatRepository repository, PublicProfileAssembler profiles) {
        this.matchService = matchService;
        this.repository = repository;
        this.profiles = profiles;
    }

    /**
     * Active matches, most recent activity (last message or match time) first.
     */
    @Transactional(readOnly = true)
    public List<MatchDto> list(UUID me) {
        return build(me, matchService.activeMatchesOf(me)).stream()
                .sorted(Comparator.comparing(MatchViews::activityAt).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<MatchDto> one(UUID me, UUID matchId) {
        return matchService.findActive(matchId, me).flatMap(match -> build(me, List.of(match)).stream().findFirst());
    }

    private List<MatchDto> build(UUID me, List<MatchInfo> matches) {
        if (matches.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = matches.stream().map(MatchInfo::id).toList();
        Map<UUID, PublicProfileDto> byAccount = profiles.byAccountIds(me, matches.stream().map(m -> m.other(me)).toList());
        Map<UUID, ChatMessage> last = repository.lastMessages(ids);
        Map<UUID, Long> unread = repository.unreadCounts(me, ids);
        Map<UUID, ChatRepository.ReadState> mine = new HashMap<>();
        Map<UUID, ChatRepository.ReadState> theirs = new HashMap<>();
        for (ChatRepository.ReadState state : repository.readStates(ids)) {
            (state.accountId().equals(me) ? mine : theirs).put(state.matchId(), state);
        }
        return matches.stream()
                .map(match -> {
                    PublicProfileDto profile = byAccount.get(match.other(me));
                    if (profile == null) {
                        return null; // the other profile is gone (account being deleted)
                    }
                    ChatMessage lastMessage = last.get(match.id());
                    ChatRepository.ReadState my = mine.get(match.id());
                    ChatRepository.ReadState other = theirs.get(match.id());
                    return new MatchDto(match.id(), match.createdAt(), profile,
                            lastMessage == null ? null : MessageDto.of(lastMessage, me),
                            unread.getOrDefault(match.id(), 0L),
                            my == null ? null : my.lastReadMessageId(),
                            other == null ? null : other.lastReadMessageId(),
                            other == null ? null : other.lastReadAt());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static Instant activityAt(MatchDto match) {
        return match.lastMessage() != null ? match.lastMessage().createdAt() : match.createdAt();
    }
}
