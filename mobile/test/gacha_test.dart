import 'package:dating_mobile/core/models/gacha.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('banner and pull result parse the contract payload', () {
    final banner = GachaBanner.fromJson({
      'code': 'STANDARD',
      'title': 'Стандартный',
      'type': 'PERMANENT',
      'open': true,
      'cost': {'itemCode': 'GACHA_TICKET', 'perPull': 1, 'multiPullSize': 10, 'multiPullCost': 10},
      'myPity': {'epicGuaranteedIn': 3, 'legendaryGuaranteedIn': 71, 'totalPulls': 9},
      'rates': {
        'totalWeight': 100,
        'rarities': [
          {'rarity': 'COMMON', 'weight': 70, 'percent': 70.0},
          {'rarity': 'LEGENDARY', 'weight': 1, 'percent': 1.0},
        ],
      },
      'featured': [
        {
          'entryId': 'premium3',
          'rarity': 'EPIC',
          'reward': {'type': 'PREMIUM_DAYS', 'item': null, 'quantity': null, 'days': 3},
          'weight': 1,
          'percent': 1.0,
          'featured': true,
        },
      ],
    });
    expect(banner.multiPullCost, 10);
    expect(banner.pity.epicGuaranteedIn, 3);
    expect(banner.rarityPercents['LEGENDARY'], 1.0);
    expect(banner.featured.single.reward.label, 'Премиум на 3 дн.');
    expect(banner.pool, isEmpty);

    final response = PullResponse.fromJson({
      'results': [
        {
          'rarity': 'RARE',
          'outcome': 'DUPLICATE_SHARDS',
          'shardsGranted': 5,
          'pityTrigger': null,
          'reward': {
            'type': 'ITEM',
            'item': {'code': 'SUPERLIKE', 'kind': 'CONSUMABLE', 'title': 'Суперлайк'},
            'quantity': 3,
          },
        },
      ],
      'pity': {'totalPulls': 10},
      'costBalance': {'itemCode': 'GACHA_TICKET', 'quantity': 4},
      'shardBalance': {'itemCode': 'GACHA_SHARD', 'quantity': 25},
    });
    final result = response.results.single;
    expect(result.duplicate, isTrue);
    expect(result.reward.label, 'Суперлайк ×3');
    expect(response.ticketsLeft, 4);
    expect(response.shards, 25);
  });

  test('shards exchange offers whole tickets up to the cap', () {
    final shards = ShardsInfo.fromJson({
      'balance': {'quantity': 95},
      'shardsPerTicket': 30,
      'maxTicketsPerExchange': 2,
    });
    expect(shards.affordableTickets, 2);
  });
}
