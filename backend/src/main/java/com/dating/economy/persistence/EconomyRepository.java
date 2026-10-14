package com.dating.economy.persistence;

import com.dating.economy.domain.ItemKind;
import com.dating.economy.domain.RewardSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Wallet storage. Plain SQL on purpose: balance changes are single atomic statements
 * (upsert / conditional update), so concurrent grants and spends never lose updates or go negative.
 * Runs inside the surrounding Spring transaction.
 */
@Repository
public class EconomyRepository {

    public record BalanceRow(String itemCode, ItemKind kind, long quantity) {
    }

    private final JdbcClient jdbc;

    public EconomyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ItemKind> findActiveItemKind(String itemCode) {
        return jdbc.sql("SELECT kind FROM catalog_items WHERE code = :code AND active")
                .param("code", itemCode)
                .query(String.class)
                .optional()
                .map(ItemKind::valueOf);
    }

    /**
     * @return false if a transaction with this idempotency key already exists for the account
     */
    public boolean insertTransaction(UUID id, UUID accountId, String type, RewardSource source,
                                     String sourceRef, String idempotencyKey) {
        return jdbc.sql("""
                        INSERT INTO economy_transactions (id, account_id, type, source, source_ref, idempotency_key)
                        VALUES (:id, :accountId, :type, :source, :sourceRef, :key)
                        ON CONFLICT (account_id, idempotency_key) DO NOTHING
                        """)
                .param("id", id)
                .param("accountId", accountId)
                .param("type", type)
                .param("source", source.name())
                .param("sourceRef", sourceRef)
                .param("key", idempotencyKey)
                .update() == 1;
    }

    public void insertLine(UUID transactionId, int lineNo, String itemCode, Long quantityDelta, Integer premiumDays) {
        jdbc.sql("""
                        INSERT INTO economy_ledger_lines (transaction_id, line_no, item_code, quantity_delta, premium_days)
                        VALUES (:tx, :lineNo, :itemCode, :delta, :days)
                        """)
                .param("tx", transactionId)
                .param("lineNo", lineNo)
                .param("itemCode", itemCode)
                .param("delta", quantityDelta)
                .param("days", premiumDays)
                .update();
    }

    public void credit(UUID accountId, String itemCode, long quantity) {
        jdbc.sql("""
                        INSERT INTO wallet_balances (account_id, item_code, quantity) VALUES (:accountId, :itemCode, :qty)
                        ON CONFLICT (account_id, item_code)
                        DO UPDATE SET quantity = wallet_balances.quantity + EXCLUDED.quantity, updated_at = now()
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .param("qty", quantity)
                .update();
    }

    /**
     * @return true if the item became owned now, false if it was already owned (duplicate)
     */
    public boolean grantOwned(UUID accountId, String itemCode) {
        return jdbc.sql("""
                        INSERT INTO wallet_balances (account_id, item_code, quantity) VALUES (:accountId, :itemCode, 1)
                        ON CONFLICT (account_id, item_code) DO NOTHING
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .update() == 1;
    }

    /**
     * Atomic conditional decrement: never goes below zero, even with parallel requests.
     */
    public boolean debit(UUID accountId, String itemCode, long quantity) {
        return jdbc.sql("""
                        UPDATE wallet_balances SET quantity = quantity - :qty, updated_at = now()
                        WHERE account_id = :accountId AND item_code = :itemCode AND quantity >= :qty
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .param("qty", quantity)
                .update() == 1;
    }

    public long balance(UUID accountId, String itemCode) {
        return jdbc.sql("SELECT quantity FROM wallet_balances WHERE account_id = :accountId AND item_code = :itemCode")
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public List<BalanceRow> balances(UUID accountId) {
        return jdbc.sql("""
                        SELECT b.item_code, c.kind, b.quantity
                        FROM wallet_balances b JOIN catalog_items c ON c.code = b.item_code
                        WHERE b.account_id = :accountId AND b.quantity > 0
                        ORDER BY c.kind, b.item_code
                        """)
                .param("accountId", accountId)
                .query((rs, n) -> new BalanceRow(rs.getString(1), ItemKind.valueOf(rs.getString(2)), rs.getLong(3)))
                .list();
    }

    /**
     * Adds days to premium, counting from now if premium has already expired. Atomic.
     */
    public Instant extendPremium(UUID accountId, int days) {
        return jdbc.sql("""
                        INSERT INTO premium_entitlements (account_id, premium_until)
                        VALUES (:accountId, now() + make_interval(days => :days))
                        ON CONFLICT (account_id) DO UPDATE
                          SET premium_until = GREATEST(premium_entitlements.premium_until, now()) + make_interval(days => :days),
                              updated_at = now()
                        RETURNING premium_until
                        """)
                .param("accountId", accountId)
                .param("days", days)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public Optional<Instant> premiumUntil(UUID accountId) {
        return jdbc.sql("SELECT premium_until FROM premium_entitlements WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    public List<UUID> accountsWithActivePremium() {
        return jdbc.sql("SELECT account_id FROM premium_entitlements WHERE premium_until > now()")
                .query(UUID.class)
                .list();
    }

    public void deleteAccountData(UUID accountId) {
        jdbc.sql("DELETE FROM economy_transactions WHERE account_id = :a").param("a", accountId).update();
        jdbc.sql("DELETE FROM wallet_balances WHERE account_id = :a").param("a", accountId).update();
        jdbc.sql("DELETE FROM premium_entitlements WHERE account_id = :a").param("a", accountId).update();
    }
}
