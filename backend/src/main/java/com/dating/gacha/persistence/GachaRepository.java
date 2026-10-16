package com.dating.gacha.persistence;

import com.dating.economy.domain.CatalogItem;
import com.dating.economy.domain.ItemKind;
import com.dating.gacha.domain.BannerType;
import com.dating.gacha.domain.GachaBanner;
import com.dating.gacha.domain.PityRules;
import com.dating.gacha.domain.PityState;
import com.dating.gacha.domain.PityTrigger;
import com.dating.gacha.domain.PoolEntry;
import com.dating.gacha.domain.PoolRewardType;
import com.dating.gacha.domain.PullOutcome;
import com.dating.gacha.domain.Rarity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gacha storage. Plain SQL like the economy module; runs inside the surrounding Spring transaction.
 * Wallet changes never happen here: they go through EconomyService.
 */
@Repository
public class GachaRepository {

    /** One stored pull request: the idempotency record of a 1-pull or multi-pull. */
    public record RequestRow(UUID id, String bannerCode, int pullCount, String costItemCode, long costQuantity) {
    }

    /** One pull of the history, with a snapshot of what was granted. */
    public record PullRow(long seq, UUID id, UUID requestId, String bannerCode, String bannerTitle, int pullIndex,
                          String entryId, PoolRewardType rewardType, CatalogItem item, Long quantity, Integer days,
                          Rarity rarity, PullOutcome outcome, int shards, PityTrigger pityTrigger, Instant createdAt) {
    }

    /** A pull to store; {@code entry} is the rolled pool entry. */
    public record NewPull(UUID id, int pullIndex, PoolEntry entry, PullOutcome outcome, int shards,
                          PityTrigger pityTrigger) {
    }

    private static final String BANNER_COLUMNS = """
            b.code, b.title, b.description, b.type, b.starts_at, b.ends_at, b.active, b.cost_item_code,
            b.cost_per_pull, b.multi_pull_size, b.pity_group, b.epic_pity, b.legendary_pity, b.soft_pity_start,
            b.soft_pity_weight_step, b.sort_order, b.attributes::text
            """;
    private static final String ITEM_COLUMNS =
            "c.code, c.kind, c.category, c.equip_slot, c.title, c.rarity, c.attributes::text";
    private static final String ENTRY_COLUMNS =
            "p.banner_code, p.entry_id, p.reward_type, p.quantity, p.days, p.rarity, p.weight, p.featured, p.sort_order, ";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    /** Namespace of the per-account advisory lock (pg_advisory_xact_lock(int, int)). */
    private static final int ACCOUNT_LOCK_NAMESPACE = 0x6AC4A;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public GachaRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // ---------------------------------------------------------------- banners

    public Optional<GachaBanner> findBanner(String code) {
        return jdbc.sql("SELECT " + BANNER_COLUMNS + " FROM gacha_banners b WHERE b.code = :code")
                .param("code", code)
                .query((rs, n) -> banner(rs))
                .optional();
    }

    /**
     * @param activeOnly only banners with the active flag (the time window is checked by the caller)
     */
    public List<GachaBanner> banners(boolean activeOnly) {
        return jdbc.sql("SELECT " + BANNER_COLUMNS + " FROM gacha_banners b"
                        + (activeOnly ? " WHERE b.active" : "") + " ORDER BY b.sort_order, b.code")
                .query((rs, n) -> banner(rs))
                .list();
    }

    public boolean insertBanner(GachaBanner b) {
        return jdbc.sql("""
                        INSERT INTO gacha_banners (code, title, description, type, starts_at, ends_at, active, cost_item_code,
                          cost_per_pull, multi_pull_size, pity_group, epic_pity, legendary_pity, soft_pity_start,
                          soft_pity_weight_step, sort_order, attributes)
                        VALUES (:code, :title, :description, :type, :startsAt, :endsAt, :active, :costItem,
                          :costPerPull, :multi, :pityGroup, :epicPity, :legendaryPity, :softStart,
                          :softStep, :sortOrder, CAST(:attributes AS jsonb))
                        ON CONFLICT (code) DO NOTHING
                        """)
                .params(bannerParams(b))
                .update() == 1;
    }

    public boolean updateBanner(GachaBanner b) {
        return jdbc.sql("""
                        UPDATE gacha_banners SET title = :title, description = :description, type = :type,
                          starts_at = :startsAt, ends_at = :endsAt, active = :active, cost_item_code = :costItem,
                          cost_per_pull = :costPerPull, multi_pull_size = :multi, pity_group = :pityGroup,
                          epic_pity = :epicPity, legendary_pity = :legendaryPity, soft_pity_start = :softStart,
                          soft_pity_weight_step = :softStep, sort_order = :sortOrder,
                          attributes = CAST(:attributes AS jsonb), updated_at = now()
                        WHERE code = :code
                        """)
                .params(bannerParams(b))
                .update() == 1;
    }

    public boolean deleteBanner(String code) {
        return jdbc.sql("DELETE FROM gacha_banners WHERE code = :code").param("code", code).update() == 1;
    }

    public long countPulls(String bannerCode) {
        return jdbc.sql("SELECT count(*) FROM gacha_pulls WHERE banner_code = :code")
                .param("code", bannerCode)
                .query(Long.class)
                .single();
    }

    // ---------------------------------------------------------------- pools

    /**
     * Entries of a banner in a stable order (sort order, then id).
     *
     * @param rollableOnly skip entries whose catalog item is deactivated (they cannot be granted)
     */
    public List<PoolEntry> pool(String bannerCode, boolean rollableOnly) {
        return pools(List.of(bannerCode), rollableOnly).getOrDefault(bannerCode, List.of());
    }

    public Map<String, List<PoolEntry>> pools(Collection<String> bannerCodes, boolean rollableOnly) {
        Map<String, List<PoolEntry>> pools = new LinkedHashMap<>();
        if (bannerCodes.isEmpty()) {
            return pools;
        }
        jdbc.sql("SELECT " + ENTRY_COLUMNS + ITEM_COLUMNS + """
                         FROM gacha_pool_entries p
                         LEFT JOIN catalog_items c ON c.code = p.item_code
                         WHERE p.banner_code IN (:codes)
                        """
                        + (rollableOnly ? " AND (p.reward_type = 'PREMIUM_DAYS' OR c.active)" : "")
                        + " ORDER BY p.banner_code, p.sort_order, p.entry_id")
                .param("codes", bannerCodes)
                .query(rs -> {
                    pools.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(entry(rs));
                });
        return pools;
    }

    public void upsertEntry(String bannerCode, PoolEntry e) {
        jdbc.sql("""
                        INSERT INTO gacha_pool_entries (banner_code, entry_id, reward_type, item_code, quantity, days,
                          rarity, weight, featured, sort_order)
                        VALUES (:banner, :entryId, :type, :itemCode, :quantity, :days, :rarity, :weight, :featured, :sortOrder)
                        ON CONFLICT (banner_code, entry_id) DO UPDATE SET reward_type = :type, item_code = :itemCode,
                          quantity = :quantity, days = :days, rarity = :rarity, weight = :weight, featured = :featured,
                          sort_order = :sortOrder
                        """)
                .param("banner", bannerCode)
                .param("entryId", e.entryId())
                .param("type", e.rewardType().name())
                .param("itemCode", e.itemCode())
                .param("quantity", e.quantity())
                .param("days", e.days())
                .param("rarity", e.rarity().name())
                .param("weight", e.weight())
                .param("featured", e.featured())
                .param("sortOrder", e.sortOrder())
                .update();
        touchBanner(bannerCode);
    }

    public boolean deleteEntry(String bannerCode, String entryId) {
        boolean deleted = jdbc.sql("DELETE FROM gacha_pool_entries WHERE banner_code = :b AND entry_id = :e")
                .param("b", bannerCode)
                .param("e", entryId)
                .update() == 1;
        touchBanner(bannerCode);
        return deleted;
    }

    private void touchBanner(String bannerCode) {
        jdbc.sql("UPDATE gacha_banners SET updated_at = now() WHERE code = :code").param("code", bannerCode).update();
    }

    // ---------------------------------------------------------------- locking and pity

    /**
     * Serializes all gacha operations of one account until the end of the transaction. Pity rows alone would
     * not be enough: duplicate detection across banners of different pity groups needs one lock per account.
     */
    public void lockAccount(UUID accountId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:ns, hashtext(:account))) l")
                .param("ns", ACCOUNT_LOCK_NAMESPACE)
                .param("account", accountId.toString())
                .query(Integer.class)
                .single();
    }

    /**
     * Creates the counter row if needed and locks it until the end of the transaction.
     */
    public PityState lockPity(UUID accountId, String pityGroup) {
        jdbc.sql("""
                        INSERT INTO gacha_pity_counters (account_id, pity_group) VALUES (:a, :g)
                        ON CONFLICT (account_id, pity_group) DO NOTHING
                        """)
                .param("a", accountId)
                .param("g", pityGroup)
                .update();
        return jdbc.sql("""
                        SELECT pulls_since_epic, pulls_since_legendary, total_pulls FROM gacha_pity_counters
                        WHERE account_id = :a AND pity_group = :g
                        FOR UPDATE
                        """)
                .param("a", accountId)
                .param("g", pityGroup)
                .query((rs, n) -> new PityState(rs.getInt(1), rs.getInt(2), rs.getLong(3)))
                .single();
    }

    public void savePity(UUID accountId, String pityGroup, PityState state) {
        jdbc.sql("""
                        INSERT INTO gacha_pity_counters (account_id, pity_group, pulls_since_epic, pulls_since_legendary, total_pulls)
                        VALUES (:a, :g, :epic, :legendary, :total)
                        ON CONFLICT (account_id, pity_group) DO UPDATE SET pulls_since_epic = :epic,
                          pulls_since_legendary = :legendary, total_pulls = :total, updated_at = now()
                        """)
                .param("a", accountId)
                .param("g", pityGroup)
                .param("epic", state.pullsSinceEpic())
                .param("legendary", state.pullsSinceLegendary())
                .param("total", state.totalPulls())
                .update();
    }

    public Map<String, PityState> pityStates(UUID accountId) {
        Map<String, PityState> states = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT pity_group, pulls_since_epic, pulls_since_legendary, total_pulls FROM gacha_pity_counters
                        WHERE account_id = :a ORDER BY pity_group
                        """)
                .param("a", accountId)
                .query(rs -> {
                    states.put(rs.getString(1), new PityState(rs.getInt(2), rs.getInt(3), rs.getLong(4)));
                });
        return states;
    }

    public void deletePity(UUID accountId) {
        jdbc.sql("DELETE FROM gacha_pity_counters WHERE account_id = :a").param("a", accountId).update();
    }

    // ---------------------------------------------------------------- pull requests and history

    public Optional<RequestRow> findRequest(UUID accountId, String idempotencyKey) {
        return jdbc.sql("""
                        SELECT id, banner_code, pull_count, cost_item_code, cost_quantity FROM gacha_pull_requests
                        WHERE account_id = :a AND idempotency_key = :k
                        """)
                .param("a", accountId)
                .param("k", idempotencyKey)
                .query((rs, n) -> new RequestRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getLong(5)))
                .optional();
    }

    public void insertRequest(UUID id, UUID accountId, String idempotencyKey, String bannerCode, int pullCount,
                              String costItemCode, long costQuantity) {
        jdbc.sql("""
                        INSERT INTO gacha_pull_requests (id, account_id, idempotency_key, banner_code, pull_count,
                          cost_item_code, cost_quantity)
                        VALUES (:id, :a, :k, :banner, :count, :costItem, :costQty)
                        """)
                .param("id", id)
                .param("a", accountId)
                .param("k", idempotencyKey)
                .param("banner", bannerCode)
                .param("count", pullCount)
                .param("costItem", costItemCode)
                .param("costQty", costQuantity)
                .update();
    }

    public void insertPull(UUID requestId, UUID accountId, GachaBanner banner, NewPull pull) {
        PoolEntry e = pull.entry();
        jdbc.sql("""
                        INSERT INTO gacha_pulls (id, request_id, account_id, banner_code, pity_group, pull_index, entry_id,
                          reward_type, item_code, quantity, days, rarity, result_type, shards, pity_trigger)
                        VALUES (:id, :request, :a, :banner, :group, :index, :entryId, :type, :itemCode, :quantity, :days,
                          :rarity, :outcome, :shards, :trigger)
                        """)
                .param("id", pull.id())
                .param("request", requestId)
                .param("a", accountId)
                .param("banner", banner.code())
                .param("group", banner.pityGroup())
                .param("index", pull.pullIndex())
                .param("entryId", e.entryId())
                .param("type", e.rewardType().name())
                .param("itemCode", e.itemCode())
                .param("quantity", e.quantity())
                .param("days", e.days())
                .param("rarity", e.rarity().name())
                .param("outcome", pull.outcome().name())
                .param("shards", pull.shards())
                .param("trigger", pull.pityTrigger() == null ? null : pull.pityTrigger().name())
                .update();
    }

    public List<PullRow> pullsOfRequest(UUID requestId) {
        return jdbc.sql(PULL_SELECT + " WHERE g.request_id = :r ORDER BY g.pull_index")
                .param("r", requestId)
                .query((rs, n) -> pullRow(rs))
                .list();
    }

    /**
     * Newest first.
     *
     * @param beforeSeq only pulls older than this cursor (null = from the newest)
     */
    public List<PullRow> history(UUID accountId, Long beforeSeq, int limit) {
        return jdbc.sql(PULL_SELECT + " WHERE g.account_id = :a AND g.seq < :before ORDER BY g.seq DESC LIMIT :limit")
                .param("a", accountId)
                .param("before", beforeSeq == null ? Long.MAX_VALUE : beforeSeq)
                .param("limit", limit)
                .query((rs, n) -> pullRow(rs))
                .list();
    }

    public long countPulls(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM gacha_pulls WHERE account_id = :a")
                .param("a", accountId)
                .query(Long.class)
                .single();
    }

    /** Pulls are removed together with their requests (ON DELETE CASCADE), freeing the idempotency keys. */
    public int deleteHistory(UUID accountId) {
        return jdbc.sql("DELETE FROM gacha_pull_requests WHERE account_id = :a").param("a", accountId).update();
    }

    private static final String PULL_SELECT = "SELECT g.seq, g.id, g.request_id, g.banner_code, b.title, g.pull_index,"
            + " g.entry_id, g.reward_type, g.quantity, g.days, g.rarity, g.result_type, g.shards, g.pity_trigger,"
            + " g.created_at, " + ITEM_COLUMNS
            + " FROM gacha_pulls g LEFT JOIN gacha_banners b ON b.code = g.banner_code"
            + " LEFT JOIN catalog_items c ON c.code = g.item_code";

    // ---------------------------------------------------------------- forced results (testing)

    /**
     * Removes and returns the oldest forced entry id for this account and banner.
     */
    public Optional<String> pollForced(UUID accountId, String bannerCode) {
        return jdbc.sql("""
                        DELETE FROM gacha_test_forced_pulls WHERE id = (
                          SELECT id FROM gacha_test_forced_pulls WHERE account_id = :a AND banner_code = :b
                          ORDER BY id LIMIT 1)
                        RETURNING entry_id
                        """)
                .param("a", accountId)
                .param("b", bannerCode)
                .query(String.class)
                .optional();
    }

    public void replaceForced(UUID accountId, String bannerCode, List<String> entryIds) {
        jdbc.sql("DELETE FROM gacha_test_forced_pulls WHERE account_id = :a AND banner_code = :b")
                .param("a", accountId)
                .param("b", bannerCode)
                .update();
        for (String entryId : entryIds) {
            jdbc.sql("INSERT INTO gacha_test_forced_pulls (account_id, banner_code, entry_id) VALUES (:a, :b, :e)")
                    .param("a", accountId)
                    .param("b", bannerCode)
                    .param("e", entryId)
                    .update();
        }
    }

    public Map<String, List<String>> forced(UUID accountId) {
        Map<String, List<String>> forced = new LinkedHashMap<>();
        jdbc.sql("SELECT banner_code, entry_id FROM gacha_test_forced_pulls WHERE account_id = :a ORDER BY banner_code, id")
                .param("a", accountId)
                .query(rs -> {
                    forced.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return forced;
    }

    public void deleteForced(UUID accountId) {
        jdbc.sql("DELETE FROM gacha_test_forced_pulls WHERE account_id = :a").param("a", accountId).update();
    }

    // ---------------------------------------------------------------- account deletion

    public void deleteAccountData(UUID accountId) {
        // gacha_pulls go with their requests (ON DELETE CASCADE)
        for (String table : List.of("gacha_pull_requests", "gacha_pity_counters", "gacha_test_forced_pulls")) {
            jdbc.sql("DELETE FROM " + table + " WHERE account_id = :a").param("a", accountId).update();
        }
    }

    // ---------------------------------------------------------------- mapping

    private Map<String, Object> bannerParams(GachaBanner b) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("code", b.code());
        params.put("title", b.title());
        params.put("description", b.description());
        params.put("type", b.type().name());
        params.put("startsAt", toOffset(b.startsAt()));
        params.put("endsAt", toOffset(b.endsAt()));
        params.put("active", b.active());
        params.put("costItem", b.costItemCode());
        params.put("costPerPull", b.costPerPull());
        params.put("multi", b.multiPullSize());
        params.put("pityGroup", b.pityGroup());
        params.put("epicPity", b.pity().epicPity());
        params.put("legendaryPity", b.pity().legendaryPity());
        params.put("softStart", b.pity().softPityStart());
        params.put("softStep", b.pity().softPityWeightStep());
        params.put("sortOrder", b.sortOrder());
        params.put("attributes", jsonMapper.writeValueAsString(b.attributes() == null ? Map.of() : b.attributes()));
        return params;
    }

    private GachaBanner banner(ResultSet rs) throws SQLException {
        String attributes = rs.getString(17);
        return new GachaBanner(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                BannerType.valueOf(rs.getString(4)),
                instant(rs, 5),
                instant(rs, 6),
                rs.getBoolean(7),
                rs.getString(8),
                rs.getInt(9),
                rs.getInt(10),
                rs.getString(11),
                new PityRules(nullableInt(rs, 12), nullableInt(rs, 13), nullableInt(rs, 14), rs.getLong(15)),
                rs.getInt(16),
                attributes == null ? Map.of() : jsonMapper.readValue(attributes, MAP)
        );
    }

    private PoolEntry entry(ResultSet rs) throws SQLException {
        return new PoolEntry(
                rs.getString(2),
                PoolRewardType.valueOf(rs.getString(3)),
                item(rs, 10),
                nullableLong(rs, 4),
                nullableInt(rs, 5),
                Rarity.valueOf(rs.getString(6)),
                rs.getLong(7),
                rs.getBoolean(8),
                rs.getInt(9)
        );
    }

    private PullRow pullRow(ResultSet rs) throws SQLException {
        String trigger = rs.getString(14);
        return new PullRow(
                rs.getLong(1),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getString(5),
                rs.getInt(6),
                rs.getString(7),
                PoolRewardType.valueOf(rs.getString(8)),
                item(rs, 16),
                nullableLong(rs, 9),
                nullableInt(rs, 10),
                Rarity.valueOf(rs.getString(11)),
                PullOutcome.valueOf(rs.getString(12)),
                rs.getInt(13),
                trigger == null ? null : PityTrigger.valueOf(trigger),
                instant(rs, 15)
        );
    }

    /** Catalog columns starting at {@code start}; null when the LEFT JOIN found no item. */
    private CatalogItem item(ResultSet rs, int start) throws SQLException {
        String code = rs.getString(start);
        if (code == null) {
            return null;
        }
        String attributes = rs.getString(start + 6);
        return new CatalogItem(
                code,
                ItemKind.valueOf(rs.getString(start + 1)),
                rs.getString(start + 2),
                rs.getString(start + 3),
                rs.getString(start + 4),
                rs.getString(start + 5),
                attributes == null ? Map.of() : jsonMapper.readValue(attributes, MAP)
        );
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
