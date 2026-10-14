package com.dating.economy.application;

import com.dating.accounts.application.AccountDeletedEvent;
import com.dating.common.errors.ApiException;
import com.dating.economy.domain.ItemKind;
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
 * The single entry point for changing a user's wallet: consumables, cosmetics, sticker packs
 * and premium days. Subscriptions, achievements, gacha, promos and support all call {@link #grant}.
 * <p>
 * Every operation is a transaction with an idempotency key unique per account, so retries
 * (webhook redelivery, double clicks, job restarts) never grant or spend twice.
 */
@Service
public class EconomyService {

    private static final int MAX_KEY_LENGTH = 150;

    private final EconomyRepository repository;
    private final ProductEventPublisher productEvents;

    public EconomyService(EconomyRepository repository, ProductEventPublisher productEvents) {
        this.repository = repository;
        this.productEvents = productEvents;
    }

    /**
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
                    ItemKind kind = requireItem(item.itemCode());
                    long applied;
                    if (kind.stackable()) {
                        repository.credit(accountId, item.itemCode(), item.quantity());
                        applied = item.quantity();
                    } else {
                        // Already-owned cosmetics are recorded as a 0-delta duplicate; a gacha module can
                        // later convert duplicates (e.g. into currency) using these ledger lines.
                        applied = repository.grantOwned(accountId, item.itemCode()) ? 1 : 0;
                    }
                    repository.insertLine(transactionId, lineNo++, item.itemCode(), applied, null);
                }
                case Reward.PremiumDays premium -> {
                    repository.extendPremium(accountId, premium.days());
                    repository.insertLine(transactionId, lineNo++, null, null, premium.days());
                }
            }
        }
        productEvents.publish("economy_granted", Map.of("source", source.name(), "rewards", rewards.size()));
        return true;
    }

    /**
     * Spends a consumable. Retrying with the same key does not spend again.
     *
     * @return remaining balance
     * @throws ApiException 409 INSUFFICIENT_BALANCE
     */
    @Transactional
    public long spend(UUID accountId, String itemCode, long quantity, String sourceRef, String idempotencyKey) {
        validateKey(idempotencyKey);
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (!requireItem(itemCode).stackable()) {
            throw new IllegalArgumentException("Only consumables can be spent: " + itemCode);
        }
        UUID transactionId = UUID.randomUUID();
        if (!repository.insertTransaction(transactionId, accountId, "SPEND", RewardSource.SPEND, sourceRef, idempotencyKey)) {
            return repository.balance(accountId, itemCode);
        }
        if (!repository.debit(accountId, itemCode, quantity)) {
            // Rolls back the transaction row too, so the same key can be retried after a top-up.
            throw ApiException.insufficientBalance(itemCode, repository.balance(accountId, itemCode));
        }
        repository.insertLine(transactionId, 0, itemCode, -quantity, null);
        productEvents.publish("economy_spent", Map.of("itemCode", itemCode, "quantity", quantity));
        return repository.balance(accountId, itemCode);
    }

    /**
     * Grants one premium allowance for a period; a no-op if already granted for that period.
     */
    @Transactional
    public void grantAllowance(UUID accountId, EconomyProperties.Allowance allowance, String periodKey) {
        if (!requireItem(allowance.itemCode()).stackable()) {
            throw new IllegalStateException("Premium allowance must be a consumable: " + allowance.itemCode());
        }
        String key = "allowance:" + allowance.itemCode() + ":" + allowance.period() + ":" + periodKey;
        UUID transactionId = UUID.randomUUID();
        if (!repository.insertTransaction(transactionId, accountId, "GRANT", RewardSource.PREMIUM_ALLOWANCE, periodKey, key)) {
            return;
        }
        long quantity = allowance.mode() == EconomyProperties.Mode.ADD
                ? allowance.quantity()
                : Math.max(0, allowance.quantity() - repository.balance(accountId, allowance.itemCode()));
        if (quantity > 0) {
            repository.credit(accountId, allowance.itemCode(), quantity);
            repository.insertLine(transactionId, 0, allowance.itemCode(), quantity, null);
        }
    }

    @Transactional(readOnly = true)
    public List<EconomyRepository.BalanceRow> balances(UUID accountId) {
        return repository.balances(accountId);
    }

    @Transactional(readOnly = true)
    public long balance(UUID accountId, String itemCode) {
        return repository.balance(accountId, itemCode);
    }

    private ItemKind requireItem(String itemCode) {
        return repository.findActiveItemKind(itemCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown or inactive catalog item: " + itemCode));
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
