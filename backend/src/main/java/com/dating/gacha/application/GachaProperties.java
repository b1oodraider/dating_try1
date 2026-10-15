package com.dating.gacha.application;

import com.dating.gacha.domain.Rarity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.EnumMap;
import java.util.Map;

/**
 * Gacha economy knobs. Defaults live here, so nothing has to be configured; override with {@code app.gacha.*}.
 * Banners, pools, costs and pity rules are data (gacha_banners / gacha_pool_entries), not configuration.
 *
 * @param shardItemCode          consumable granted for duplicates (catalog item)
 * @param duplicateShards        shards for a duplicate cosmetic by its rarity in the pool
 *                               (defaults: COMMON 1, RARE 5, EPIC 20, LEGENDARY 60; missing keys use the defaults)
 * @param exchangeTicketItemCode what shards are exchanged for
 * @param shardsPerTicket        exchange rate: this many shards → 1 ticket
 * @param maxTicketsPerExchange  upper bound of one exchange request
 */
@ConfigurationProperties(prefix = "app.gacha")
public record GachaProperties(
        @DefaultValue("GACHA_SHARD") String shardItemCode,
        Map<Rarity, Integer> duplicateShards,
        @DefaultValue("GACHA_TICKET") String exchangeTicketItemCode,
        @DefaultValue("30") int shardsPerTicket,
        @DefaultValue("100") int maxTicketsPerExchange
) {

    private static final Map<Rarity, Integer> DEFAULT_SHARDS = Map.of(
            Rarity.COMMON, 1, Rarity.RARE, 5, Rarity.EPIC, 20, Rarity.LEGENDARY, 60);

    public GachaProperties {
        Map<Rarity, Integer> shards = new EnumMap<>(DEFAULT_SHARDS);
        if (duplicateShards != null) {
            shards.putAll(duplicateShards);
        }
        if (shards.values().stream().anyMatch(v -> v == null || v < 0)) {
            throw new IllegalStateException("app.gacha.duplicate-shards must be >= 0");
        }
        if (shardsPerTicket < 1 || maxTicketsPerExchange < 1) {
            throw new IllegalStateException("app.gacha.shards-per-ticket and max-tickets-per-exchange must be >= 1");
        }
        duplicateShards = Map.copyOf(shards);
    }

    public int shardsFor(Rarity rarity) {
        return duplicateShards.getOrDefault(rarity, 0);
    }
}
