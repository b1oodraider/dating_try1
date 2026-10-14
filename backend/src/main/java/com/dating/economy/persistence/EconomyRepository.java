package com.dating.economy.persistence;

import com.dating.economy.domain.Bucket;
import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.ItemKind;
import com.dating.economy.domain.RewardSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wallet storage. Plain SQL on purpose: balance changes are single atomic statements or happen under
 * row locks, so concurrent grants and spends never lose updates or go negative.
 * Runs inside the surrounding Spring transaction.
 */
@Repository
public class EconomyRepository {

    public record BalanceRow(CatalogItem item, long quantity, long periodicQuantity) {
    }

    public record DecorationRow(UUID accountId, String slot, CatalogItem item) {
    }

    private static final String ITEM_COLUMNS =
            "c.code, c.kind, c.category, c.equip_slot, c.title, c.rarity, c.attributes::text";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public EconomyRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // ---------------------------------------------------------------- catalog

    public Optional<CatalogItem> findActiveItem(String itemCode) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM catalog_items c WHERE c.code = :code AND c.active")
                .param("code", itemCode)
                .query((rs, n) -> item(rs, 1))
                .optional();
    }

    // ---------------------------------------------------------------- ledger

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

    public void insertLine(UUID transactionId, int lineNo, String itemCode, Long quantityDelta,
                           Integer premiumDays, Bucket bucket) {
        jdbc.sql("""
                        INSERT INTO economy_ledger_lines (transaction_id, line_no, item_code, quantity_delta, premium_days, bucket)
                        VALUES (:tx, :lineNo, :itemCode, :delta, :days, :bucket)
                        """)
                .param("tx", transactionId)
                .param("lineNo", lineNo)
                .param("itemCode", itemCode)
                .param("delta", quantityDelta)
                .param("days", premiumDays)
                .param("bucket", bucket == null ? null : bucket.name())
                .update();
    }

    /**
     * Testing support: forget a transaction so the same idempotency key can be granted again.
     */
    public void deleteTransactionByKey(UUID accountId, String idempotencyKey) {
        jdbc.sql("DELETE FROM economy_transactions WHERE account_id = :a AND idempotency_key = :k")
                .param("a", accountId)
                .param("k", idempotencyKey)
                .update();
    }

    // ---------------------------------------------------------------- balances

    public void credit(UUID accountId, String itemCode, Bucket bucket, long quantity) {
        jdbc.sql("""
                        INSERT INTO wallet_balances (account_id, item_code, bucket, quantity)
                        VALUES (:accountId, :itemCode, :bucket, :qty)
                        ON CONFLICT (account_id, item_code, bucket)
                        DO UPDATE SET quantity = wallet_balances.quantity + EXCLUDED.quantity, updated_at = now()
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .param("bucket", bucket.name())
                .param("qty", quantity)
                .update();
    }

    /**
     * @return true if the item became owned now, false if it was already owned (duplicate)
     */
    public boolean grantOwned(UUID accountId, String itemCode) {
        return jdbc.sql("""
                        INSERT INTO wallet_balances (account_id, item_code, bucket, quantity)
                        VALUES (:accountId, :itemCode, 'PERMANENT', 1)
                        ON CONFLICT (account_id, item_code, bucket) DO UPDATE SET quantity = 1, updated_at = now()
                        WHERE wallet_balances.quantity = 0
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .update() == 1;
    }

    /**
     * Locks the balance rows of one item until the end of the transaction and returns them.
     */
    public Map<Bucket, Long> lockBalances(UUID accountId, String itemCode) {
        Map<Bucket, Long> balances = new EnumMap<>(Bucket.class);
        jdbc.sql("""
                        SELECT bucket, quantity FROM wallet_balances
                        WHERE account_id = :accountId AND item_code = :itemCode
                        FOR UPDATE
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .query(rs -> {
                    balances.put(Bucket.valueOf(rs.getString(1)), rs.getLong(2));
                });
        return balances;
    }

    public void debit(UUID accountId, String itemCode, Bucket bucket, long quantity) {
        int updated = jdbc.sql("""
                        UPDATE wallet_balances SET quantity = quantity - :qty, updated_at = now()
                        WHERE account_id = :accountId AND item_code = :itemCode AND bucket = :bucket AND quantity >= :qty
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .param("bucket", bucket.name())
                .param("qty", quantity)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Balance changed under lock: " + itemCode + "/" + bucket);
        }
    }

    public void setBalance(UUID accountId, String itemCode, Bucket bucket, long quantity) {
        jdbc.sql("""
                        INSERT INTO wallet_balances (account_id, item_code, bucket, quantity)
                        VALUES (:accountId, :itemCode, :bucket, :qty)
                        ON CONFLICT (account_id, item_code, bucket) DO UPDATE SET quantity = :qty, updated_at = now()
                        """)
                .param("accountId", accountId)
                .param("itemCode", itemCode)
                .param("bucket", bucket.name())
                .param("qty", quantity)
                .update();
    }

    public long balance(UUID accountId, String itemCode) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM wallet_balances WHERE account_id = :a AND item_code = :c")
                .param("a", accountId)
                .param("c", itemCode)
                .query(Long.class)
                .single();
    }

    public long balance(UUID accountId, String itemCode, Bucket bucket) {
        return jdbc.sql("SELECT quantity FROM wallet_balances WHERE account_id = :a AND item_code = :c AND bucket = :b")
                .param("a", accountId)
                .param("c", itemCode)
                .param("b", bucket.name())
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public List<BalanceRow> balances(UUID accountId) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + """
                        , SUM(b.quantity), SUM(CASE WHEN b.bucket = 'PERIODIC' THEN b.quantity ELSE 0 END)
                        FROM wallet_balances b JOIN catalog_items c ON c.code = b.item_code
                        WHERE b.account_id = :accountId
                        GROUP BY c.code
                        HAVING SUM(b.quantity) > 0
                        ORDER BY c.kind, c.category, c.code
                        """)
                .param("accountId", accountId)
                .query((rs, n) -> new BalanceRow(item(rs, 1), rs.getLong(8), rs.getLong(9)))
                .list();
    }

    // ---------------------------------------------------------------- premium

    /**
     * Adds days to premium, counting from {@code now} if premium has already expired. Atomic.
     */
    public Instant extendPremium(UUID accountId, int days, Instant now) {
        return jdbc.sql("""
                        INSERT INTO premium_entitlements (account_id, premium_until)
                        VALUES (:accountId, CAST(:now AS timestamptz) + make_interval(days => :days))
                        ON CONFLICT (account_id) DO UPDATE
                          SET premium_until = GREATEST(premium_entitlements.premium_until, CAST(:now AS timestamptz))
                                              + make_interval(days => :days),
                              updated_at = now()
                        RETURNING premium_until
                        """)
                .param("accountId", accountId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("days", days)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    public void expirePremium(UUID accountId, Instant now) {
        jdbc.sql("UPDATE premium_entitlements SET premium_until = :now, updated_at = now() WHERE account_id = :a AND premium_until > :now")
                .param("a", accountId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
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

    // ---------------------------------------------------------------- clock (test accounts)

    public long clockOffsetSeconds(UUID accountId) {
        return jdbc.sql("SELECT offset_seconds FROM account_clock_offsets WHERE account_id = :a")
                .param("a", accountId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    public void setClockOffsetSeconds(UUID accountId, long offsetSeconds) {
        jdbc.sql("""
                        INSERT INTO account_clock_offsets (account_id, offset_seconds) VALUES (:a, :s)
                        ON CONFLICT (account_id) DO UPDATE SET offset_seconds = :s, updated_at = now()
                        """)
                .param("a", accountId)
                .param("s", offsetSeconds)
                .update();
    }

    // ---------------------------------------------------------------- cosmetics

    public Map<String, String> equipped(UUID accountId) {
        Map<String, String> equipped = new LinkedHashMap<>();
        jdbc.sql("SELECT slot, item_code FROM equipped_cosmetics WHERE account_id = :a ORDER BY slot")
                .param("a", accountId)
                .query(rs -> {
                    equipped.put(rs.getString(1), rs.getString(2));
                });
        return equipped;
    }

    public void equip(UUID accountId, String slot, String itemCode) {
        jdbc.sql("""
                        INSERT INTO equipped_cosmetics (account_id, slot, item_code) VALUES (:a, :slot, :code)
                        ON CONFLICT (account_id, slot) DO UPDATE SET item_code = :code, updated_at = now()
                        """)
                .param("a", accountId)
                .param("slot", slot)
                .param("code", itemCode)
                .update();
    }

    public void unequip(UUID accountId, String slot) {
        jdbc.sql("DELETE FROM equipped_cosmetics WHERE account_id = :a AND slot = :slot")
                .param("a", accountId)
                .param("slot", slot)
                .update();
    }

    /**
     * Equipped items in the given slots that are still active and still owned.
     */
    public List<DecorationRow> decorations(Collection<UUID> accountIds, Collection<String> slots) {
        if (accountIds.isEmpty() || slots.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT e.account_id, e.slot, " + ITEM_COLUMNS + """
                         FROM equipped_cosmetics e
                         JOIN catalog_items c ON c.code = e.item_code AND c.active
                         JOIN wallet_balances b ON b.account_id = e.account_id AND b.item_code = e.item_code AND b.quantity > 0
                         WHERE e.account_id IN (:ids) AND e.slot IN (:slots)
                        """)
                .param("ids", accountIds)
                .param("slots", slots)
                .query((rs, n) -> new DecorationRow(rs.getObject(1, UUID.class), rs.getString(2), item(rs, 3)))
                .list();
    }

    // ---------------------------------------------------------------- account deletion

    public void deleteAccountData(UUID accountId) {
        for (String table : List.of("economy_transactions", "equipped_cosmetics", "wallet_balances",
                "premium_entitlements", "account_clock_offsets")) {
            jdbc.sql("DELETE FROM " + table + " WHERE account_id = :a").param("a", accountId).update();
        }
    }

    private CatalogItem item(ResultSet rs, int start) throws SQLException {
        String attributes = rs.getString(start + 6);
        return new CatalogItem(
                rs.getString(start),
                ItemKind.valueOf(rs.getString(start + 1)),
                rs.getString(start + 2),
                rs.getString(start + 3),
                rs.getString(start + 4),
                rs.getString(start + 5),
                attributes == null ? Map.of() : jsonMapper.readValue(attributes, MAP)
        );
    }
}
