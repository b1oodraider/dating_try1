import 'json_utils.dart';
import 'wallet.dart';

/// What a pool entry gives (contract `GachaReward`).
class GachaReward {
  const GachaReward({required this.type, this.item, this.quantity, this.days});

  /// ITEM or PREMIUM_DAYS.
  final String type;

  /// Rendered like a wallet item; null for premium days.
  final WalletItem? item;
  final int? quantity;
  final int? days;

  String get label {
    if (type == 'PREMIUM_DAYS') return 'Премиум на ${days ?? 0} дн.';
    final title = item?.displayTitle ?? 'Предмет';
    final qty = quantity ?? 1;
    return qty > 1 ? '$title ×$qty' : title;
  }

  factory GachaReward.fromJson(Map<String, dynamic> json) {
    final item = json['item'];
    return GachaReward(
      type: asString(json['type'], 'ITEM'),
      item: item is Map<String, dynamic> ? WalletItem.fromJson(item) : null,
      quantity: asIntOrNull(json['quantity']),
      days: asIntOrNull(json['days']),
    );
  }
}

class PoolEntry {
  const PoolEntry({
    required this.entryId,
    required this.rarity,
    required this.reward,
    required this.percent,
    required this.featured,
  });

  final String entryId;
  final String rarity;
  final GachaReward reward;

  /// Base chance of one pull, 0..100.
  final double percent;
  final bool featured;

  factory PoolEntry.fromJson(Map<String, dynamic> json) => PoolEntry(
        entryId: asString(json['entryId']),
        rarity: asString(json['rarity']),
        reward: GachaReward.fromJson(asJsonMap(json['reward'])),
        percent: asDoubleOrNull(json['percent']) ?? 0,
        featured: asBool(json['featured']),
      );
}

/// Pity counters of a banner group (contract `PityProgress`).
class PityProgress {
  const PityProgress({
    this.epicGuaranteedIn,
    this.legendaryGuaranteedIn,
    this.totalPulls = 0,
  });

  /// 1 = the next pull is guaranteed; null = no such guarantee.
  final int? epicGuaranteedIn;
  final int? legendaryGuaranteedIn;
  final int totalPulls;

  factory PityProgress.fromJson(Map<String, dynamic> json) => PityProgress(
        epicGuaranteedIn: asIntOrNull(json['epicGuaranteedIn']),
        legendaryGuaranteedIn: asIntOrNull(json['legendaryGuaranteedIn']),
        totalPulls: asInt(json['totalPulls']),
      );
}

class GachaBanner {
  const GachaBanner({
    required this.code,
    required this.title,
    required this.description,
    required this.limited,
    required this.endsAt,
    required this.open,
    required this.costItemCode,
    required this.costPerPull,
    required this.multiPullSize,
    required this.multiPullCost,
    required this.pity,
    required this.rarityPercents,
    required this.featured,
    required this.pool,
  });

  final String code;
  final String title;
  final String? description;
  final bool limited;
  final DateTime? endsAt;

  /// Pulls are possible now.
  final bool open;
  final String costItemCode;
  final int costPerPull;
  final int multiPullSize;
  final int multiPullCost;
  final PityProgress pity;

  /// rarity → base chance of one pull, in the order of the server.
  final Map<String, double> rarityPercents;
  final List<PoolEntry> featured;

  /// Only from `GET /gacha/banners/{code}`.
  final List<PoolEntry> pool;

  factory GachaBanner.fromJson(Map<String, dynamic> json) {
    final cost = asJsonMap(json['cost']);
    final rates = <String, double>{};
    for (final r in asList<Map<String, dynamic>>(
        asJsonMap(json['rates'])['rarities'], (m) => m)) {
      rates[asString(r['rarity'])] = asDoubleOrNull(r['percent']) ?? 0;
    }
    return GachaBanner(
      code: asString(json['code']),
      title: asString(json['title']),
      description: asStringOrNull(json['description']),
      limited: json['type'] == 'LIMITED',
      endsAt: asDateTimeOrNull(json['endsAt']),
      open: asBool(json['open'], true),
      costItemCode: asString(cost['itemCode'], 'GACHA_TICKET'),
      costPerPull: asInt(cost['perPull'], 1),
      multiPullSize: asInt(cost['multiPullSize'], 10),
      multiPullCost: asInt(cost['multiPullCost'], 10),
      pity: PityProgress.fromJson(asJsonMap(json['myPity'])),
      rarityPercents: rates,
      featured: asList<PoolEntry>(json['featured'], PoolEntry.fromJson),
      pool: asList<PoolEntry>(json['pool'], PoolEntry.fromJson),
    );
  }
}

class PullResult {
  const PullResult({
    required this.rarity,
    required this.reward,
    required this.duplicate,
    required this.shardsGranted,
    this.pityTrigger,
  });

  final String rarity;
  final GachaReward reward;

  /// An owned cosmetic: shards were credited instead.
  final bool duplicate;
  final int shardsGranted;

  /// EPIC_PITY, LEGENDARY_PITY, FORCED or null.
  final String? pityTrigger;

  factory PullResult.fromJson(Map<String, dynamic> json) => PullResult(
        rarity: asString(json['rarity']),
        reward: GachaReward.fromJson(asJsonMap(json['reward'])),
        duplicate: json['outcome'] == 'DUPLICATE_SHARDS',
        shardsGranted: asInt(json['shardsGranted']),
        pityTrigger: asStringOrNull(json['pityTrigger']),
      );
}

class PullResponse {
  const PullResponse({
    required this.results,
    required this.pity,
    required this.ticketsLeft,
    required this.shards,
  });

  final List<PullResult> results;
  final PityProgress pity;
  final int ticketsLeft;
  final int shards;

  factory PullResponse.fromJson(Map<String, dynamic> json) => PullResponse(
        results: asList<PullResult>(json['results'], PullResult.fromJson),
        pity: PityProgress.fromJson(asJsonMap(json['pity'])),
        ticketsLeft: asInt(asJsonMap(json['costBalance'])['quantity']),
        shards: asInt(asJsonMap(json['shardBalance'])['quantity']),
      );
}

class GachaHistoryItem {
  const GachaHistoryItem({
    required this.id,
    required this.bannerTitle,
    required this.rarity,
    required this.reward,
    required this.duplicate,
    required this.shardsGranted,
    this.createdAt,
  });

  final int id;
  final String bannerTitle;
  final String rarity;
  final GachaReward reward;
  final bool duplicate;
  final int shardsGranted;
  final DateTime? createdAt;

  factory GachaHistoryItem.fromJson(Map<String, dynamic> json) =>
      GachaHistoryItem(
        id: asInt(json['id']),
        bannerTitle: asString(json['bannerTitle']),
        rarity: asString(json['rarity']),
        reward: GachaReward.fromJson(asJsonMap(json['reward'])),
        duplicate: json['outcome'] == 'DUPLICATE_SHARDS',
        shardsGranted: asInt(json['shardsGranted']),
        createdAt: asDateTimeOrNull(json['createdAt']),
      );
}

class GachaHistoryPage {
  const GachaHistoryPage({required this.items, this.nextBefore});

  final List<GachaHistoryItem> items;
  final int? nextBefore;

  factory GachaHistoryPage.fromJson(Map<String, dynamic> json) =>
      GachaHistoryPage(
        items: asList<GachaHistoryItem>(json['items'], GachaHistoryItem.fromJson),
        nextBefore: asIntOrNull(json['nextBefore']),
      );
}

/// `GET /gacha/shards`.
class ShardsInfo {
  const ShardsInfo({
    required this.balance,
    required this.shardsPerTicket,
    required this.maxTicketsPerExchange,
  });

  final int balance;
  final int shardsPerTicket;
  final int maxTicketsPerExchange;

  int get affordableTickets => shardsPerTicket <= 0
      ? 0
      : (balance ~/ shardsPerTicket).clamp(0, maxTicketsPerExchange);

  factory ShardsInfo.fromJson(Map<String, dynamic> json) => ShardsInfo(
        balance: asInt(asJsonMap(json['balance'])['quantity']),
        shardsPerTicket: asInt(json['shardsPerTicket'], 30),
        maxTicketsPerExchange: asInt(json['maxTicketsPerExchange'], 100),
      );
}
