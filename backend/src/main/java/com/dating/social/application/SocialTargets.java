package com.dating.social.application;

import com.dating.accounts.domain.Account;
import com.dating.accounts.persistence.AccountRepository;
import com.dating.common.errors.ApiException;
import com.dating.profiles.domain.Profile;
import com.dating.profiles.domain.PublicationStatus;
import com.dating.profiles.domain.VisibilityStatus;
import com.dating.profiles.persistence.ProfileRepository;
import com.dating.social.persistence.SocialRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves the profile id from the API to the other person's account and enforces who may act on whom.
 * Every "you cannot see this person" case answers 404 PROFILE_NOT_FOUND, so a block or the
 * test/real isolation is never revealed.
 */
@Component
public class SocialTargets {

    /** The other person: internal account id plus the public profile id. */
    public record Counterpart(UUID accountId, UUID profileId) {
    }

    private final ProfileRepository profileRepository;
    private final AccountRepository accountRepository;
    private final SocialRepository repository;

    public SocialTargets(ProfileRepository profileRepository, AccountRepository accountRepository,
                         SocialRepository repository) {
        this.profileRepository = profileRepository;
        this.accountRepository = accountRepository;
        this.repository = repository;
    }

    /**
     * Target of a like / super like / pass: a PUBLISHED, public profile of an active account of the same kind
     * (test vs real), not me, and nobody blocked anybody.
     */
    @Transactional(readOnly = true)
    public Counterpart requireDiscoverable(UUID me, UUID profileId) {
        Profile profile = profileRepository.findById(profileId).orElseThrow(SocialTargets::profileNotFound);
        Counterpart counterpart = check(me, profile);
        if (profile.getPublicationStatus() != PublicationStatus.PUBLISHED
                || profile.getVisibilityStatus() != VisibilityStatus.PUBLIC
                || repository.isBlockedEitherWay(me, counterpart.accountId())) {
            throw profileNotFound();
        }
        return counterpart;
    }

    /**
     * Target of a block or a report: any existing profile of an active account of the same kind, not me
     * (a hidden profile or an existing block does not prevent blocking / reporting).
     */
    @Transactional(readOnly = true)
    public Counterpart requireCounterpart(UUID me, UUID profileId) {
        Profile profile = profileRepository.findById(profileId).orElseThrow(SocialTargets::profileNotFound);
        return check(me, profile);
    }

    private Counterpart check(UUID me, Profile profile) {
        if (profile.getAccountId().equals(me)) {
            throw ApiException.badRequest("SELF_ACTION", "Это действие нельзя выполнить со своей анкетой");
        }
        Account other = accountRepository.findById(profile.getAccountId()).orElseThrow(SocialTargets::profileNotFound);
        boolean meIsTest = accountRepository.findById(me).map(Account::isTestAccount).orElse(false);
        if (!other.isActive() || other.isTestAccount() != meIsTest) {
            throw profileNotFound();
        }
        return new Counterpart(other.getId(), profile.getId());
    }

    static ApiException profileNotFound() {
        return ApiException.notFound("PROFILE_NOT_FOUND", "Анкета не найдена");
    }
}
