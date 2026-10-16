package com.dating.gacha.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.common.testing.TestAccountsProperties;
import com.dating.economy.application.EconomyClock;
import com.dating.economy.application.EconomyService;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import com.dating.gacha.api.BalanceDto;
import com.dating.gacha.api.BannerDto;
import com.dating.gacha.api.GachaRewardDto;
import com.dating.gacha.api.HistoryPageDto;
import com.dating.gacha.api.PityProgressDto;
import com.dating.gacha.api.PityRulesDto;
import com.dating.gacha.api.PullResponseDto;
import com.dating.gacha.api.PullResultDto;
import com.dating.gacha.api.ShardExchangeResultDto;
import com.dating.gacha.api.ShardsDto;
import com.dating.gacha.domain.GachaBanner;
import com.dating.gacha.domain.GachaRoller;
import com.dating.gacha.domain.PityState;
import com.dating.gacha.domain.PoolEntry;
import com.dating.gacha.domain.PullOutcome;
import com.dating.gacha.domain.RateTable;
import com.dating.gacha.domain.Rarity;
import com.dating.gacha.persistence.GachaRepository;
import com.dating.gacha.persistence.GachaRepository.NewPull;
import com.dating.gacha.persistence.GachaRepository.PullRow;
import com.dating.gacha.persistence.GachaRepository.RequestRow;
import com.dating.productevents.application.ProductEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gacha for users: banners with disclosed rates, pulls, history and the shard exchange.
 * <p>
 * A pull is one transaction: the cost is spent and the rewards are granted through {@link EconomyService}
 * (ledger, idempotency keys {@code gacha:spend:<requestId>} / {@code gacha:<requestId>}), the pity counters
 * are updated under a row lock and the results are stored in the history. The client's idempotency key maps to
 * one stored pull request, so a retry returns the stored results without spending again.
 */
@Service
public class GachaService {

    private static final int PREVIEW_SIZE = 3;
    public static final int MAX_HISTORY_LIMIT = 100;

    private final GachaRepository repository;
    private final EconomyService economyService;
    private final EconomyClock clock;
    private final GachaRandom random;
    private final GachaProperties properties;
    private final TestAccountsProperties testing;
    private final ProductEventPublisher productEvents;

    public GachaService(GachaRepository repository, EconomyService economyService, EconomyClock clock,
                        GachaRandom random, GachaProperties properties, TestAccountsProperties testing,
                        ProductEventPublisher productEvents) {
        this.repository = repository;
        this.economyService = economyService;
        this.clock = clock;
        this.random = random;
        this.properties = properties;
        this.testing = testing;
        this.productEvents = productEvents;
    }

    // ---------------------------------------------------------------- banners

    /**
     * Banners the caller can pull on right now (economy time of the account), with rates and own pity progress.
     */
    @Transactional(readOnly = true)
    public List<BannerDto> openBanners(UUID accountId) {
        Instant now = clock.now(accountId);
        List<GachaBanner> banners = repository.banners(true).stream().filter(b -> b.openAt(now)).toList();
        Map<String, List<PoolEntry>> pools = repository.pools(banners.stream().map(GachaBanner::code).toList(), true);
        Map<String, PityState> pity = repository.pityStates(accountId);
        return banners.stream()
                .filter(b -> !pools.getOrDefault(b.code(), List.of()).isEmpty())
                .map(b -> bannerDto(b, pools.get(b.code()), pity, now, false))
                .toList();
    }

    /**
     * One banner with its full pool. Ended banners stay visible (rates of past pulls); banners that are
     * deactivated or have not started yet are not found.
     */
    @Transactional(readOnly = true)
    public BannerDto banner(UUID accountId, String code) {
        Instant now = clock.now(accountId);
        GachaBanner banner = repository.findBanner(code)
                .filter(GachaBanner::active)
                .filter(b -> b.startsAt() == null || !now.isBefore(b.startsAt()))
                .orElseThrow(() -> GachaErrors.bannerNotFound(code));
        return bannerDto(banner, repository.pool(code, true), repository.pityStates(accountId), now, true);
    }

    // ---------------------------------------------------------------- pull

    /**
     * @param count 1 or the banner's multi-pull size
     * @throws ApiException 404 BANNER_NOT_FOUND, 409 BANNER_NOT_ACTIVE, 400 INVALID_PULL_COUNT,
     *                      409 INSUFFICIENT_BALANCE (nothing changes), 409 IDEMPOTENCY_KEY_REUSED
     */
    @Transactional
    public PullResponseDto pull(UUID accountId, String bannerCode, int count, String idempotencyKey) {
        // One gacha operation per account at a time: pity, duplicate detection and the forced queue stay consistent.
        repository.lockAccount(accountId);

        RequestRow existing = repository.findRequest(accountId, idempotencyKey).orElse(null);
        if (existing != null) {
            if (!existing.bannerCode().equals(bannerCode) || existing.pullCount() != count) {
                throw GachaErrors.idempotencyKeyReused();
            }
            return response(accountId, existing.id(), existing.bannerCode(), existing.pullCount(),
                    existing.costItemCode(), true);
        }

        GachaBanner banner = repository.findBanner(bannerCode)
                .orElseThrow(() -> GachaErrors.bannerNotFound(bannerCode));
        if (!banner.openAt(clock.now(accountId))) {
            throw GachaErrors.bannerNotActive();
        }
        if (count != 1 && count != banner.multiPullSize()) {
            throw GachaErrors.invalidPullCount(banner.multiPullSize());
        }
        List<PoolEntry> pool = repository.pool(bannerCode, true);
        if (pool.isEmpty()) {
            throw GachaErrors.bannerNotActive();
        }

        UUID requestId = UUID.randomUUID();
        long cost = (long) banner.costPerPull() * count;
        repository.insertRequest(requestId, accountId, idempotencyKey, bannerCode, count, banner.costItemCode(), cost);
        // 409 INSUFFICIENT_BALANCE rolls back the whole transaction, including the request row.
        economyService.spend(accountId, banner.costItemCode(), cost, requestId.toString(), "gacha:spend:" + requestId);

        PityState state = repository.lockPity(accountId, banner.pityGroup());
        Map<String, PoolEntry> byId = pool.stream().collect(Collectors.toMap(PoolEntry::entryId, Function.identity()));
        boolean forcedQueue = testing.enabled();

        List<NewPull> pulls = new ArrayList<>(count);
        List<Reward> rewards = new ArrayList<>();
        Set<String> ownedInThisRequest = new HashSet<>();
        long shardsTotal = 0;
        for (int i = 0; i < count; i++) {
            PoolEntry forced = forcedQueue
                    ? repository.pollForced(accountId, bannerCode).map(byId::get).orElse(null)
                    : null;
            GachaRoller.Roll roll = GachaRoller.roll(pool, banner.pity(), state, random.generator(), forced);
            state = roll.stateAfter();
            PoolEntry entry = roll.entry();

            PullOutcome outcome = PullOutcome.NEW;
            int shards = 0;
            if (entry.ownable()
                    && (ownedInThisRequest.contains(entry.itemCode())
                    || economyService.balance(accountId, entry.itemCode()) > 0)) {
                outcome = PullOutcome.DUPLICATE_SHARDS;
                shards = properties.shardsFor(entry.rarity());
                shardsTotal += shards;
            } else {
                if (entry.ownable()) {
                    ownedInThisRequest.add(entry.itemCode());
                }
                rewards.add(entry.toReward());
            }
            pulls.add(new NewPull(UUID.randomUUID(), i, entry, outcome, shards, roll.trigger()));
        }
        if (shardsTotal > 0) {
            rewards.add(new Reward.Item(properties.shardItemCode(), shardsTotal));
        }
        if (!rewards.isEmpty()) {
            economyService.grant(accountId, RewardSource.GACHA, requestId.toString(), "gacha:" + requestId, rewards);
        }
        repository.savePity(accountId, banner.pityGroup(), state);
        for (NewPull pull : pulls) {
            repository.insertPull(requestId, accountId, banner, pull);
        }

        productEvents.publish("gacha_pulled", Map.of(
                "banner", bannerCode,
                "count", count,
                "epic", pulls.stream().filter(p -> p.entry().rarity() == Rarity.EPIC).count(),
                "legendary", pulls.stream().filter(p -> p.entry().rarity() == Rarity.LEGENDARY).count(),
                "duplicates", pulls.stream().filter(p -> p.outcome() == PullOutcome.DUPLICATE_SHARDS).count()));
        return response(accountId, requestId, bannerCode, count, banner.costItemCode(), false);
    }

    private PullResponseDto response(UUID accountId, UUID requestId, String bannerCode, int count,
                                     String costItemCode, boolean replayed) {
        List<PullResultDto> results = repository.pullsOfRequest(requestId).stream()
                .map(r -> new PullResultDto(r.pullIndex(), r.entryId(), r.rarity(), reward(r),
                        r.outcome() == PullOutcome.NEW, r.outcome(), r.shards(), r.pityTrigger()))
                .toList();
        int shards = results.stream().mapToInt(PullResultDto::shardsGranted).sum();
        PityProgressDto pity = repository.findBanner(bannerCode)
                .map(b -> PityProgressDto.of(b.pityGroup(), b.pity(),
                        repository.pityStates(accountId).getOrDefault(b.pityGroup(), PityState.ZERO)))
                .orElse(null);
        return new PullResponseDto(requestId, bannerCode, count, replayed, results, shards, pity,
                balance(accountId, costItemCode), balance(accountId, properties.shardItemCode()));
    }

    // ---------------------------------------------------------------- history

    /**
     * @param before cursor from the previous page ({@code nextBefore}), null for the newest pulls
     * @param limit  1..100
     */
    @Transactional(readOnly = true)
    public HistoryPageDto history(UUID accountId, Long before, int limit) {
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных",
                    Map.of("limit", "От 1 до " + MAX_HISTORY_LIMIT));
        }
        List<PullRow> rows = repository.history(accountId, before, limit);
        List<HistoryPageDto.Item> items = rows.stream()
                .map(r -> new HistoryPageDto.Item(r.id(), r.requestId(), r.bannerCode(), r.bannerTitle(),
                        r.pullIndex(), r.entryId(), r.rarity(), reward(r), r.outcome() == PullOutcome.NEW,
                        r.outcome(), r.shards(), r.pityTrigger(), r.createdAt()))
                .toList();
        Long nextBefore = rows.size() == limit ? rows.getLast().seq() : null;
        return new HistoryPageDto(items, nextBefore);
    }

    // ---------------------------------------------------------------- shards

    @Transactional(readOnly = true)
    public ShardsDto shards(UUID accountId) {
        return new ShardsDto(balance(accountId, properties.shardItemCode()), properties.exchangeTicketItemCode(),
                properties.shardsPerTicket(), properties.maxTicketsPerExchange(), properties.duplicateShards());
    }

    /**
     * Turns shards into tickets at the configured rate. Idempotent per key: a retry changes nothing and
     * returns {@code applied = false} with current balances.
     *
     * @throws ApiException 409 INSUFFICIENT_BALANCE (details.itemCode = the shard item)
     */
    @Transactional
    public ShardExchangeResultDto exchangeShards(UUID accountId, int tickets, String idempotencyKey) {
        if (tickets < 1 || tickets > properties.maxTicketsPerExchange()) {
            throw ApiException.validation("VALIDATION_ERROR", "Ошибка валидации входных данных",
                    Map.of("tickets", "От 1 до " + properties.maxTicketsPerExchange()));
        }
        long shardsCost = (long) tickets * properties.shardsPerTicket();
        String key = "gacha-exchange:" + idempotencyKey;
        economyService.spend(accountId, properties.shardItemCode(), shardsCost, "shard-exchange", key + ":spend");
        boolean applied = economyService.grant(accountId, RewardSource.GACHA, "shard-exchange", key,
                List.of(new Reward.Item(properties.exchangeTicketItemCode(), tickets)));
        if (applied) {
            productEvents.publish("gacha_shards_exchanged", Map.of("tickets", tickets));
        }
        return new ShardExchangeResultDto(applied, tickets, shardsCost,
                balance(accountId, properties.shardItemCode()), balance(accountId, properties.exchangeTicketItemCode()));
    }

    // ---------------------------------------------------------------- mapping

    BannerDto bannerDto(GachaBanner banner, List<PoolEntry> pool, Map<String, PityState> pity, Instant now,
                        boolean withPool) {
        RateTable rates = RateTable.of(pool);
        List<BannerDto.Entry> entries = GachaViews.entries(pool, rates);
        List<BannerDto.Entry> featured = entries.stream().filter(BannerDto.Entry::featured).toList();
        if (featured.isEmpty()) {
            featured = entries.stream()
                    .sorted(Comparator.comparing(BannerDto.Entry::rarity).reversed())
                    .limit(PREVIEW_SIZE)
                    .toList();
        }
        return new BannerDto(banner.code(), banner.title(), banner.description(), banner.type(), banner.startsAt(),
                banner.endsAt(), banner.openAt(now) && !pool.isEmpty(), banner.attributes(),
                new BannerDto.Cost(banner.costItemCode(), banner.costPerPull(), banner.multiPullSize(),
                        (long) banner.costPerPull() * banner.multiPullSize()),
                PityRulesDto.of(banner.pity()),
                PityProgressDto.of(banner.pityGroup(), banner.pity(),
                        pity.getOrDefault(banner.pityGroup(), PityState.ZERO)),
                GachaViews.rates(rates),
                featured,
                withPool ? entries : null);
    }

    private static GachaRewardDto reward(PullRow row) {
        return GachaRewardDto.of(row.rewardType(), row.item(), row.quantity(), row.days());
    }

    private BalanceDto balance(UUID accountId, String itemCode) {
        return new BalanceDto(itemCode, economyService.balance(accountId, itemCode));
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAccountData(event.accountId());
    }
}
