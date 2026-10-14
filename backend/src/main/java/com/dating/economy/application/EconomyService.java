package com.dating.economy.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.economy.domain.Bucket;
import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.Reward;
import com.dating.economy.domain.RewardSource;
import com.dating.economy.persistence.EconomyRepository;
import com.dating.productevents.application.ProductEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single entry point for changing a user's wallet: consumables, cosmetics and premium days.
 * Subscriptions, achievements, gacha, promos, support and the testing API all call {@link #grant}.
 * <p>
 * Every operation is a transaction with an idempotency key unique per account, so retries
 * (webhook redelivery, double clicks, job restarts) never grant or spend twice.
 */
@Service
public class EconomyService {

    private static final int MAX_KEY_LENGTH = 150;

    private final EconomyRepository repository;
    private final EconomyClock clock;
    private final ProductEventPublisher productEvents;

    public EconomyService(EconomyRepository repository, EconomyClock clock, ProductEventPublisher productEvents) {
        this.repository = repository;
        this.clock = clock;
        this.productEvents = productEvents;
    }

    /**
     * Grants rewards. Consumables go to the PERMANENT bucket; cosmetics become owned
     * (an already-owned cosmetic is recorded as a 0-delta duplicate that gacha can convert later).
     *
     * @return true if applied now, false if this idempotency key was already applied
     */
    @Transactional
    public boolean grant(UUID accountId, RewardSource source, String sourceRef, String idempotencyKey,
                         List<Reward> rewards) {
        validateKey(idempotencyKey);
        if (rewards == null || rewards.isEmpty()) {
            throw new IllegalArgumentException("A grant needs at least one reward");
        }
        UUID transactionId = UUID.randomUUID();
        if (!repository.insertTransaction(transactionId, accountId, "GRANT", source, sourceRef, idempotencyKey)) {
            return false;
        }
        int lineNo = 0;
        for (Reward reward : rewards) {
            switch (reward) {
                case Reward.Item item -> {
                    CatalogItem catalogItem = requireItem(item.itemCode());
                    long applied;
                    if (catalogItem.kind().stackable()) {
                        repository.credit(accountId, item.itemCode(), Bucket.PERMANENT, item.quantity());
                        applied = item.quantity();
                    } else {
                        applied = repository.grantOwned(accountId, item.itemCode()) ? 1 : 0;
                    }
                    repository.insertLine(transactionId, lineNo++, item.itemCode(), applied, null, Bucket.PERMANENT);
                }
                case Reward.PremiumDays premium -> {
                    repository.extendPremium(accountId, premium.days(), clock.now(accountId));
                    repository.insertLine(transactionId, lineNo++, null, null, premium.days(), null);
                }
            }
        }
        productEvents.publish("economy_granted", Map.of("source", source.name(), "rewards", rewards.size()));
        return true;
    }

    /**
     * Spends a consumable: PERIODIC units first (they would be refilled anyway), then PERMANENT.
     * Retrying with the same key does not spend again.
     *
     * @return remaining total balance
     * @throws ApiException 409 INSUFFICIENT_BALANCE
     */
    @Transactional
    public long spend(UUID accountId, String itemCode, long quantity, String sourceRef, String idempotencyKey) {
        validateKey(idempotencyKey);
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (!requireItem(itemCode).kind().stackable()) {
            throw new IllegalArgumentException("Only consumables can be spent: " + itemCode);
        }
        UUID transactionId = UUID.randomUUID();
        if (!repository.insertTransaction(transactionId, accountId, "SPEND", RewardSource.SPEND, sourceRef, idempotencyKey)) {
            return repository.balance(accountId, itemCode);
        }
        Map<Bucket, Long> balances = repository.lockBalances(accountId, itemCode);
        long periodic = balances.getOrDefault(Bucket.PERIODIC, 0L);
        long permanent = balances.getOrDefault(Bucket.PERMANENT, 0L);
        if (periodic + permanent < quantity) {
            // Rolls back the transaction row too, so the same key can be retried after a top-up.
            throw ApiException.insufficientBalance(itemCode, periodic + permanent);
        }
        long fromPeriodic = Math.min(quantity, periodic);
        long fromPermanent = quantity - fromPeriodic;
        int lineNo = 0;
        if (fromPeriodic > 0) {
            repository.debit(accountId, itemCode, Bucket.PERIODIC, fromPeriodic);
            repository.insertLine(transactionId, lineNo++, itemCode, -fromPeriodic, null, Bucket.PERIODIC);
        }
        if (fromPermanent > 0) {
            repository.debit(accountId, itemCode, Bucket.PERMANENT, fromPermanent);
            repository.insertLine(transactionId, lineNo, itemCode, -fromPermanent, null, Bucket.PERMANENT);
        }
        productEvents.publish("economy_spent", Map.of("itemCode", itemCode, "quantity", quantity));
        return periodic + permanent - quantity;
    }

    /**
     * Grants one premium allowance for a period into the PERIODIC bucket; a no-op if already granted.
     * TOP_UP refills the periodic units up to the quantity (bought units are not counted), ADD adds.
     */
    @Transactional
    public void grantAllowance(UUID accountId, EconomyProperties.Allowance allowance, String periodKey) {
        if (!requireItem(allowance.itemCode()).kind().stackable()) {
            throw new IllegalStateException("Premium allowance must be a consumable: " + allowance.itemCode());
        }
        String key = "allowance:" + allowance.itemCode() + ":" + allowance.period() + ":" + periodKey;
        UUID transactionId = UUID.randomUUID();
        if (!repository.insertTransaction(transactionId, accountId, "GRANT", RewardSource.PREMIUM_ALLOWANCE, periodKey, key)) {
            return;
        }
        long quantity = allowance.mode() == EconomyProperties.Mode.ADD
                ? allowance.quantity()
                : Math.max(0, allowance.quantity() - repository.balance(accountId, allowance.itemCode(), Bucket.PERIODIC));
        if (quantity > 0) {
            repository.credit(accountId, allowance.itemCode(), Bucket.PERIODIC, quantity);
            repository.insertLine(transactionId, 0, allowance.itemCode(), quantity, null, Bucket.PERIODIC);
        }
    }

    /**
     * Testing support: sets the PERMANENT balance of a consumable (or ownership of a cosmetic) exactly.
     */
    @Transactional
    public void setPermanentBalance(UUID accountId, String itemCode, long quantity) {
        CatalogItem item = requireItem(itemCode);
        if (quantity < 0 || (!item.kind().stackable() && quantity > 1)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Некорректное количество для " + itemCode);
        }
        repository.setBalance(accountId, itemCode, Bucket.PERMANENT, quantity);
    }

    /**
     * Testing support: forgets a transaction (and its ledger lines) so the same idempotency key can be
     * applied again. Balances are not changed.
     */
    @Transactional
    public void forgetTransaction(UUID accountId, String idempotencyKey) {
        repository.deleteTransactionByKey(accountId, idempotencyKey);
    }

    /**
     * Testing support: ends premium "now" (in the account's economy time).
     */
    @Transactional
    public void expirePremium(UUID accountId) {
        repository.expirePremium(accountId, clock.now(accountId));
    }

    @Transactional(readOnly = true)
    public List<EconomyRepository.BalanceRow> balances(UUID accountId) {
        return repository.balances(accountId);
    }

    @Transactional(readOnly = true)
    public long balance(UUID accountId, String itemCode) {
        return repository.balance(accountId, itemCode);
    }

    public CatalogItem requireItem(String itemCode) {
        return repository.findActiveItem(itemCode)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "Предмет не найден: " + itemCode));
    }

    private static void validateKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey is required (<= " + MAX_KEY_LENGTH + " chars)");
        }
    }

    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        repository.deleteAccountData(event.accountId());
    }
}
