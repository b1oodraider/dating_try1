package com.dating.testing;

import com.dating.accounts.application.AccountService;
import com.dating.common.errors.ApiException;
import com.dating.economy.application.CosmeticsService;
import com.dating.economy.application.EconomyService;
import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import com.dating.media.application.MediaService;
import com.dating.profiles.api.MyProfileDto;
import com.dating.profiles.api.PhotoDto;
import com.dating.profiles.api.ProfileUpdateDto;
import com.dating.profiles.application.ProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Ready-made QA profiles ("seed profiles"): phone-less test accounts with a filled profile, photos,
 * optional premium and equipped cosmetics. Being test accounts, they are visible only to test accounts.
 * Photos are uploaded one per request (the multipart request limit is one photo).
 */
@Service
@ConditionalOnProperty(prefix = "app.testing", name = "enabled", havingValue = "true")
public class TestProfilesService {

    /**
     * @param profile     same fields as PUT /me/profile
     * @param premiumDays premium for the seed profile (badge in the feed); 0/null = none
     * @param cosmetics   item codes to grant; equippable ones (frames, statuses, wallpapers) are equipped
     */
    public record CreateRequest(
            @NotNull(message = "Заполните profile") @Valid ProfileUpdateDto profile,
            @Min(0) @Max(3650) Integer premiumDays,
            @Size(max = 10) List<String> cosmetics
    ) {
    }

    public record SeedProfileDto(UUID accountId, MyProfileDto profile) {
    }

    private final AccountService accountService;
    private final ProfileService profileService;
    private final MediaService mediaService;
    private final EconomyService economyService;
    private final CosmeticsService cosmeticsService;

    public TestProfilesService(AccountService accountService, ProfileService profileService, MediaService mediaService,
                               EconomyService economyService, CosmeticsService cosmeticsService) {
        this.accountService = accountService;
        this.profileService = profileService;
        this.mediaService = mediaService;
        this.economyService = economyService;
        this.cosmeticsService = cosmeticsService;
    }

    /** Creates a DRAFT seed profile; add photos and publish it with the other calls. */
    @Transactional
    public SeedProfileDto create(CreateRequest request) {
        UUID accountId = accountService.createSeedAccount();
        profileService.updateProfile(accountId, request.profile());
        if (request.premiumDays() != null && request.premiumDays() > 0) {
            economyService.grant(accountId, RewardSource.TESTING, "seed-premium", "testing:seed-premium:" + accountId,
                    List.of(new Reward.PremiumDays(request.premiumDays())));
        }
        if (request.cosmetics() != null) {
            for (String itemCode : request.cosmetics()) {
                CatalogItem item = economyService.requireItem(itemCode);
                economyService.grant(accountId, RewardSource.TESTING, itemCode, "testing:seed-item:" + accountId + ":" + itemCode,
                        List.of(new Reward.Item(itemCode, 1)));
                if (item.equipSlot() != null) {
                    cosmeticsService.equip(accountId, item.equipSlot(), itemCode);
                }
            }
        }
        return dto(accountId);
    }

    @Transactional
    public SeedProfileDto update(UUID accountId, ProfileUpdateDto update) {
        requireSeed(accountId);
        profileService.updateProfile(accountId, update);
        return dto(accountId);
    }

    @Transactional
    public PhotoDto addPhoto(UUID accountId, MultipartFile file) {
        requireSeed(accountId);
        return mediaService.uploadPhoto(accountId, file);
    }

    @Transactional
    public SeedProfileDto publish(UUID accountId) {
        requireSeed(accountId);
        profileService.publishProfile(accountId);
        return dto(accountId);
    }

    @Transactional
    public List<SeedProfileDto> list() {
        return accountService.activeSeedAccountIds().stream().map(this::dto).toList();
    }

    @Transactional
    public void delete(UUID accountId) {
        requireSeed(accountId);
        accountService.deleteAccount(accountId);
    }

    @Transactional
    public int deleteAll() {
        List<UUID> ids = accountService.activeSeedAccountIds();
        ids.forEach(accountService::deleteAccount);
        return ids.size();
    }

    private SeedProfileDto dto(UUID accountId) {
        return new SeedProfileDto(accountId, profileService.getMyProfile(accountId));
    }

    private void requireSeed(UUID accountId) {
        if (!accountService.isActiveSeedAccount(accountId)) {
            throw ApiException.notFound("SEED_PROFILE_NOT_FOUND", "Тестовый профиль не найден");
        }
    }
}
