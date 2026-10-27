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
import com.dating.profiles.domain.Gender;
import com.dating.social.application.ReactionService;
import com.dating.social.domain.ReactionType;
import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
    private final ReactionService reactionService;
    private final JsonMapper jsonMapper;

    private static final List<String> FEMALE_NAMES = List.of("Алёна", "Вера", "Дина", "Ева", "Жанна", "Зоя", "Инна",
            "Карина", "Лиля", "Майя", "Нина", "Олеся", "Полина", "Рита", "Света", "Тая", "Ульяна", "Яна", "Ксения",
            "Мила");
    private static final List<String> MALE_NAMES = List.of("Артём", "Борис", "Глеб", "Денис", "Егор", "Захар", "Илья",
            "Кирилл", "Лев", "Марк", "Никита", "Олег", "Павел", "Роман", "Савва", "Тимур", "Фёдор", "Юрий", "Ян",
            "Вадим");

    public TestProfilesService(AccountService accountService, ProfileService profileService, MediaService mediaService,
                               EconomyService economyService, CosmeticsService cosmeticsService,
                               ReactionService reactionService, JsonMapper jsonMapper) {
        this.accountService = accountService;
        this.profileService = profileService;
        this.mediaService = mediaService;
        this.economyService = economyService;
        this.cosmeticsService = cosmeticsService;
        this.reactionService = reactionService;
        this.jsonMapper = jsonMapper;
    }

    public record CrowdResult(int created, int liked) {
    }

    /**
     * A crowd for checking the feed and mass likes (the like-spam rule): [count] published seed profiles that
     * match the caller (the gender they look for, their age range and city), with photos copied from the existing
     * seed profiles of that gender. With [likeMe] every one of them likes the caller.
     */
    @Transactional
    public CrowdResult crowd(UUID me, int count, boolean likeMe) {
        MyProfileDto mine = profileService.getMyProfile(me);
        if (mine.gender() == null || mine.seeking() == null || mine.city() == null) {
            throw ApiException.badRequest("PROFILE_INCOMPLETE", "Сначала укажите в своей анкете пол, кого ищете и город");
        }
        // Seed photos of each gender the caller looks for (ANY: both, the crowd alternates).
        Map<Gender, List<UUID>> photos = new LinkedHashMap<>();
        for (UUID seed : accountService.activeSeedAccountIds()) {
            MyProfileDto profile = profileService.getMyProfile(seed);
            if (profile.gender() != null && mine.seeking().accepts(profile.gender()) && !profile.photos().isEmpty()) {
                photos.computeIfAbsent(profile.gender(), g -> new ArrayList<>()).add(profile.photos().getFirst().id());
            }
        }
        if (photos.isEmpty()) {
            throw ApiException.badRequest("NO_SEED_PHOTOS",
                    "Нет тестовых анкет нужного пола с фото: сначала загрузите анкеты из seed/");
        }
        List<Gender> genders = List.copyOf(photos.keySet());
        int ageTo = mine.ageTo() == null ? Math.min(mine.ageFrom() + 10, 60) : mine.ageTo();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int liked = 0;
        for (int i = 0; i < count; i++) {
            UUID accountId = accountService.createSeedAccount();
            int age = random.nextInt(mine.ageFrom(), ageTo + 1);
            Gender gender = genders.get(i % genders.size());
            List<String> names = gender == Gender.FEMALE ? FEMALE_NAMES : MALE_NAMES;
            List<UUID> genderPhotos = photos.get(gender);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("displayName", names.get(random.nextInt(names.size())));
            fields.put("birthDate", LocalDate.now().minusYears(age).minusDays(random.nextInt(1, 360)).toString());
            fields.put("gender", gender.name());
            fields.put("seeking", mine.gender().name());
            if (mine.cityId() != null) {
                fields.put("cityId", mine.cityId());
            } else {
                fields.put("city", mine.city());
            }
            fields.put("aboutText", "Тестовая анкета из толпы №" + (i + 1));
            profileService.updateProfile(accountId, jsonMapper.convertValue(fields, ProfileUpdateDto.class));
            mediaService.copyPhotoForTesting(genderPhotos.get(i % genderPhotos.size()), accountId);
            profileService.publishProfile(accountId);
            if (likeMe) {
                reactionService.reactForTesting(accountId, me, ReactionType.LIKE);
                liked++;
            }
        }
        return new CrowdResult(count, liked);
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
