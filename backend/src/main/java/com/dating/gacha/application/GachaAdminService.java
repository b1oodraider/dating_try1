package com.dating.gacha.application;

import com.dating.common.errors.ApiException;
import com.dating.economy.application.EconomyService;
import com.dating.economy.domain.CatalogItem;
import com.dating.gacha.api.AdminBannerDto;
import com.dating.gacha.api.AdminBannerRequest;
import com.dating.gacha.api.AdminPoolEntryRequest;
import com.dating.gacha.api.PityRulesDto;
import com.dating.gacha.domain.BannerType;
import com.dating.gacha.domain.GachaBanner;
import com.dating.gacha.domain.PityRules;
import com.dating.gacha.domain.PoolEntry;
import com.dating.gacha.domain.PoolRewardType;
import com.dating.gacha.domain.RateTable;
import com.dating.gacha.domain.Rarity;
import com.dating.gacha.persistence.GachaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Banner and pool management for content editors (contests, collaborations, rate changes) without releases.
 * Authorization is the caller's job (see {@link com.dating.common.security.AdminAccess}); every change is logged
 * with the editor's account id.
 */
@Service
public class GachaAdminService {

    private static final Logger log = LoggerFactory.getLogger(GachaAdminService.class);
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{2,50}");
    private static final Pattern ENTRY_ID = Pattern.compile("[A-Za-z0-9_-]{1,50}");
    private static final int DEFAULT_EPIC_PITY = 10;
    private static final int DEFAULT_LEGENDARY_PITY = 80;
    private static final String DEFAULT_COST_ITEM = "GACHA_TICKET";

    private final GachaRepository repository;
    private final EconomyService economyService;

    public GachaAdminService(GachaRepository repository, EconomyService economyService) {
        this.repository = repository;
        this.economyService = economyService;
    }

    // ---------------------------------------------------------------- banners

    @Transactional(readOnly = true)
    public List<AdminBannerDto> listBanners() {
        List<GachaBanner> banners = repository.banners(false);
        Map<String, List<PoolEntry>> pools = repository.pools(banners.stream().map(GachaBanner::code).toList(), false);
        return banners.stream().map(b -> dto(b, pools.getOrDefault(b.code(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public AdminBannerDto getBanner(String code) {
        return dto(requireBanner(code), repository.pool(code, false));
    }

    /**
     * @throws ApiException 400 VALIDATION_ERROR, 404 ITEM_NOT_FOUND (cost item), 409 BANNER_ALREADY_EXISTS
     */
    @Transactional
    public AdminBannerDto createBanner(UUID editorId, AdminBannerRequest request) {
        if (request.code() == null || !CODE.matcher(request.code()).matches()) {
            throw fieldError("code", "2–50 символов: A–Z, 0–9, _");
        }
        GachaBanner banner = toBanner(request.code(), request);
        if (!repository.insertBanner(banner)) {
            throw new ApiException("BANNER_ALREADY_EXISTS", "Баннер с таким кодом уже есть", HttpStatus.CONFLICT);
        }
        log.info("Gacha admin: account {} created banner {}", editorId, banner.code());
        return getBanner(banner.code());
    }

    /**
     * Full replacement of the banner settings (the pool is managed separately).
     */
    @Transactional
    public AdminBannerDto updateBanner(UUID editorId, String code, AdminBannerRequest request) {
        requireBanner(code);
        repository.updateBanner(toBanner(code, request));
        log.info("Gacha admin: account {} updated banner {}", editorId, code);
        return getBanner(code);
    }

    /**
     * Deletes a banner that was never pulled; one with pulls must be deactivated instead (history keeps its code).
     *
     * @throws ApiException 409 BANNER_HAS_PULLS
     */
    @Transactional
    public void deleteBanner(UUID editorId, String code) {
        requireBanner(code);
        if (repository.countPulls(code) > 0) {
            throw new ApiException("BANNER_HAS_PULLS",
                    "По баннеру уже были крутки — его можно только выключить (active = false)", HttpStatus.CONFLICT);
        }
        repository.deleteBanner(code);
        log.info("Gacha admin: account {} deleted banner {}", editorId, code);
    }

    // ---------------------------------------------------------------- pool

    /**
     * Creates or replaces one entry. Rates of everybody's next pulls change immediately.
     */
    @Transactional
    public AdminBannerDto putPoolEntry(UUID editorId, String bannerCode, String entryId, AdminPoolEntryRequest request) {
        requireBanner(bannerCode);
        if (entryId == null || !ENTRY_ID.matcher(entryId).matches()) {
            throw fieldError("entryId", "1–50 символов: буквы, цифры, _ и -");
        }
        repository.upsertEntry(bannerCode, toEntry(entryId, request));
        log.info("Gacha admin: account {} put pool entry {}/{}", editorId, bannerCode, entryId);
        return getBanner(bannerCode);
    }

    /**
     * @throws ApiException 404 POOL_ENTRY_NOT_FOUND
     */
    @Transactional
    public AdminBannerDto deletePoolEntry(UUID editorId, String bannerCode, String entryId) {
        requireBanner(bannerCode);
        if (!repository.deleteEntry(bannerCode, entryId)) {
            throw GachaErrors.entryNotFound(bannerCode, entryId);
        }
        log.info("Gacha admin: account {} deleted pool entry {}/{}", editorId, bannerCode, entryId);
        return getBanner(bannerCode);
    }

    // ---------------------------------------------------------------- validation and mapping

    private GachaBanner requireBanner(String code) {
        return repository.findBanner(code).orElseThrow(() -> GachaErrors.bannerNotFound(code));
    }

    private GachaBanner toBanner(String code, AdminBannerRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (r.type() == BannerType.LIMITED && (r.startsAt() == null || r.endsAt() == null)) {
            errors.put("endsAt", "У временного баннера должны быть начало и конец");
        }
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) {
            errors.put("endsAt", "Конец должен быть позже начала");
        }
        String pityGroup = r.pityGroup() == null || r.pityGroup().isBlank() ? code : r.pityGroup();
        if (!CODE.matcher(pityGroup).matches()) {
            errors.put("pityGroup", "2–50 символов: A–Z, 0–9, _");
        }
        String costItemCode = r.costItemCode() == null || r.costItemCode().isBlank() ? DEFAULT_COST_ITEM : r.costItemCode();
        if (!errors.isEmpty()) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных", errors);
        }
        if (!economyService.requireItem(costItemCode).kind().stackable()) {
            throw fieldError("costItemCode", "Ценой крутки может быть только расходник");
        }
        PityRules pity = new PityRules(
                offIfZero(r.epicPity(), DEFAULT_EPIC_PITY),
                offIfZero(r.legendaryPity(), DEFAULT_LEGENDARY_PITY),
                offIfZero(r.softPityStart(), null),
                r.softPityWeightStep() == null ? 0 : r.softPityWeightStep());
        return new GachaBanner(code, r.title().trim(), r.description(), r.type(), r.startsAt(), r.endsAt(),
                r.active() == null || r.active(), costItemCode,
                r.costPerPull() == null ? 1 : r.costPerPull(),
                r.multiPullSize() == null ? 10 : r.multiPullSize(),
                pityGroup, pity,
                r.sortOrder() == null ? 0 : r.sortOrder(),
                r.attributes() == null ? Map.of() : r.attributes());
    }

    private PoolEntry toEntry(String entryId, AdminPoolEntryRequest r) {
        boolean featured = Boolean.TRUE.equals(r.featured());
        int sortOrder = r.sortOrder() == null ? 0 : r.sortOrder();
        if (r.rewardType() == PoolRewardType.PREMIUM_DAYS) {
            if (r.days() == null) {
                throw fieldError("days", "Укажите количество дней премиума");
            }
            if (r.itemCode() != null || r.quantity() != null) {
                throw fieldError("itemCode", "У дней премиума нет предмета и количества");
            }
            if (r.rarity() == null) {
                throw fieldError("rarity", "Укажите редкость");
            }
            return new PoolEntry(entryId, PoolRewardType.PREMIUM_DAYS, null, null, r.days(), r.rarity(),
                    r.weight(), featured, sortOrder);
        }

        if (r.itemCode() == null || r.itemCode().isBlank()) {
            throw fieldError("itemCode", "Укажите предмет");
        }
        if (r.days() != null) {
            throw fieldError("days", "Дни указываются только для премиума");
        }
        CatalogItem item = economyService.requireItem(r.itemCode());
        long quantity = r.quantity() == null ? 1 : r.quantity();
        if (!item.kind().stackable() && quantity != 1) {
            throw fieldError("quantity", "Косметика выдаётся в одном экземпляре");
        }
        Rarity catalogRarity = item.rarity() == null ? null : Rarity.valueOf(item.rarity());
        Rarity rarity = r.rarity() != null ? r.rarity() : catalogRarity;
        if (rarity == null) {
            throw fieldError("rarity", "Укажите редкость");
        }
        if (!item.kind().stackable() && catalogRarity != null && catalogRarity != rarity) {
            throw fieldError("rarity", "Редкость косметики должна совпадать с каталогом: " + catalogRarity);
        }
        return new PoolEntry(entryId, PoolRewardType.ITEM, item, quantity, null, rarity, r.weight(), featured, sortOrder);
    }

    private AdminBannerDto dto(GachaBanner b, List<PoolEntry> pool) {
        RateTable rates = RateTable.of(pool);
        return new AdminBannerDto(b.code(), b.title(), b.description(), b.type(), b.startsAt(), b.endsAt(),
                b.active(), b.costItemCode(), b.costPerPull(), b.multiPullSize(), b.pityGroup(),
                PityRulesDto.of(b.pity()), b.sortOrder(), b.attributes(), GachaViews.rates(rates),
                GachaViews.entries(pool, rates), repository.countPulls(b.code()));
    }

    private static Integer offIfZero(Integer value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value == 0 ? null : value;
    }

    private static ApiException fieldError(String field, String message) {
        return ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных", Map.of(field, message));
    }
}
