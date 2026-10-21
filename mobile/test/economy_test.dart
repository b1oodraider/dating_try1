import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/api/api_exception.dart';
import 'package:dating_mobile/core/format.dart';
import 'package:dating_mobile/core/models/account.dart';
import 'package:dating_mobile/core/models/achievement.dart';
import 'package:dating_mobile/core/models/decoration.dart';
import 'package:dating_mobile/core/models/json_utils.dart';
import 'package:dating_mobile/core/models/public_profile.dart';
import 'package:dating_mobile/core/models/testing_state.dart';
import 'package:dating_mobile/core/models/wallet.dart';
import 'package:dating_mobile/features/testing/test_panel_screen.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response _json(Object? body, int status) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

Map<String, dynamic> _walletJson() => {
      'premium': {'active': true, 'until': '2026-10-21T10:00:00Z'},
      'premiumFeatures': ['SEE_LIKES', 7],
      'items': [
        {
          'code': 'SUPERLIKE',
          'kind': 'CONSUMABLE',
          'category': 'SUPERLIKE',
          'title': 'Суперлайк',
          'rarity': null,
          'equipSlot': null,
          'attributes': {'stub': true, 'icon': 'star'},
          'quantity': 7,
          'periodicQuantity': 5.0,
        },
        {
          'code': 'FRAME_STUB_NEON',
          'kind': 'COSMETIC',
          'category': 'PHOTO_FRAME',
          'title': 'Неоновая рамка (тест)',
          'rarity': 'LEGENDARY',
          'equipSlot': 'PHOTO_FRAME',
          'attributes': {
            'stub': true,
            'borderColor': '#22D3EE',
            'borderWidth': 3,
            'glow': true,
          },
          'quantity': 1,
          'periodicQuantity': 0,
        },
        {
          'code': 'STICKERS_STUB_CATS',
          'kind': 'COSMETIC',
          'category': 'STICKER_PACK',
          'title': 'Котики (тест)',
          'rarity': 'COMMON',
          'equipSlot': null,
          'attributes': {
            'emoji': '🐱',
            'stickers': ['cat_wave', 'cat_heart'],
          },
          'quantity': 1,
          'periodicQuantity': 0,
        },
        'not-an-object',
        {'kind': 'COSMETIC'}, // no code: dropped
      ],
      'equipped': {
        'PHOTO_FRAME': 'FRAME_STUB_NEON',
        'FUTURE_SLOT': 'X',
        'BROKEN': 42,
      },
    };

Map<String, dynamic> _stateJson() => {
      'accountId': 'acc-1',
      'clockOffsetDays': 7.5,
      'economyNow': '2026-10-21T12:00:00Z',
      'wallet': _walletJson(),
      'achievements': [
        {
          'code': 'STUB_PROFILE_PUBLISHED',
          'title': 'Анкета опубликована (тест)',
          'description': 'Опубликовать анкету',
          'completed': true,
          'completedAt': '2026-10-14T09:00:00Z',
          'rewards': [
            {'type': 'ITEM', 'itemCode': 'FRAME_STUB_GOLD', 'quantity': 1},
            {'type': 'PREMIUM_DAYS', 'days': 3},
            {'type': 'SOMETHING_NEW'},
          ],
        },
      ],
    };

/// Client whose every request is captured and answered with a TestingState.
({DatingApiClient client, List<http.Request> requests}) _testingClient() {
  final requests = <http.Request>[];
  final client = DatingApiClient(
    baseUrl: 'http://api.test/api/v1',
    httpClient: MockClient((request) async {
      requests.add(request);
      return _json(_stateJson(), 200);
    }),
  );
  client.token = 'tkn';
  return (client: client, requests: requests);
}

Object? _body(http.Request request) =>
    request.body.isEmpty ? null : jsonDecode(request.body);

void main() {
  group('parsing', () {
    test('Wallet parses the contract payload defensively', () {
      final wallet = Wallet.fromJson(_walletJson());

      expect(wallet.premium.active, isTrue);
      expect(wallet.premium.until, DateTime.utc(2026, 10, 21, 10));
      expect(wallet.premiumFeatures, ['SEE_LIKES']);
      expect(wallet.items.map((i) => i.code), [
        'SUPERLIKE',
        'FRAME_STUB_NEON',
        'STICKERS_STUB_CATS',
      ]);

      final superlike = wallet.items.first;
      expect(superlike.isConsumable, isTrue);
      expect(superlike.quantity, 7);
      expect(superlike.periodicQuantity, 5);
      expect(superlike.equipSlot, isNull);
      expect(superlike.attributes['icon'], 'star');

      final frame = wallet.items[1];
      expect(frame.isEquippable, isTrue);
      expect(frame.rarity, 'LEGENDARY');
      expect(wallet.isEquipped(frame), isTrue);
      expect(wallet.isEquipped(wallet.items[2]), isFalse);

      expect(wallet.consumables, hasLength(1));
      expect(wallet.cosmetics, hasLength(2));
      expect(wallet.equipped, {
        'PHOTO_FRAME': 'FRAME_STUB_NEON',
        'FUTURE_SLOT': 'X',
      });
    });

    test('Wallet tolerates an empty or malformed body', () {
      final wallet = Wallet.fromJson(const <String, dynamic>{
        'premium': 'yes',
        'items': 'none',
        'equipped': ['PHOTO_FRAME'],
      });
      expect(wallet.premium.active, isFalse);
      expect(wallet.premium.until, isNull);
      expect(wallet.items, isEmpty);
      expect(wallet.equipped, isEmpty);
    });

    test('PublicProfile decorations: known slots render, unknown ignored', () {
      final profile = PublicProfile.fromJson({
        'id': 'p1',
        'displayName': 'Анна',
        'decorations': {
          'PHOTO_FRAME': {
            'code': 'FRAME_STUB_NEON',
            'title': 'Неоновая рамка (тест)',
            'rarity': 'LEGENDARY',
            'attributes': {
              'borderColor': '#22D3EE',
              'borderWidth': 3,
              'glow': true,
              'unknownKey': {'nested': 1},
            },
          },
          'PROFILE_STATUS': {
            'code': 'STATUS_STUB_BOOKWORM',
            'title': 'Книжный червь (тест)',
            'rarity': 'EPIC',
            'attributes': {
              'emoji': '📚',
              'label': 'Книжный червь',
              'color': '#7C3AED',
            },
          },
          'AURA': {'code': 'X', 'attributes': {}},
          'BROKEN': 'not-an-object',
        },
      });

      expect(profile.decorations.keys, ['PHOTO_FRAME', 'PROFILE_STATUS', 'AURA']);

      final frame = PhotoFrameStyle.fromAttributes(
        profile.decorations[CosmeticSlots.photoFrame]!.attributes,
      );
      expect(frame, isNotNull);
      expect(frame!.color, 0xFF22D3EE);
      expect(frame.width, 3);
      expect(frame.glow, isTrue);

      final status = ProfileStatusStyle.fromAttributes(
        profile.decorations[CosmeticSlots.profileStatus]!.attributes,
      );
      expect(status, isNotNull);
      expect(status!.text, '📚 Книжный червь');
      expect(status.color, 0xFF7C3AED);
      expect(
        profile.decorations[CosmeticSlots.profileStatus]!.rarity,
        'EPIC',
      );
    });

    test('decorations missing or malformed → empty, bad attributes → null', () {
      expect(PublicProfile.fromJson({'id': 'p'}).decorations, isEmpty);
      expect(
        PublicProfile.fromJson({'id': 'p', 'decorations': []}).decorations,
        isEmpty,
      );

      final badFrame = ProfileDecoration.fromJson({
        'code': 'F',
        'attributes': {'borderColor': 'gold', 'borderWidth': 4},
      });
      expect(PhotoFrameStyle.fromAttributes(badFrame.attributes), isNull);

      final hugeFrame = PhotoFrameStyle.fromAttributes(
        const {'borderColor': 'D4AF37', 'borderWidth': 99},
      );
      expect(hugeFrame!.width, PhotoFrameStyle.maxWidth);
      expect(hugeFrame.glow, isFalse);

      expect(ProfileStatusStyle.fromAttributes(const {'color': '#000000'}),
          isNull);
      final noColor =
          ProfileStatusStyle.fromAttributes(const {'label': 'Привет'});
      expect(noColor!.color, isNull);
      expect(noColor.text, 'Привет');

      final noAttributes = ProfileDecoration.fromJson({'attributes': 'x'});
      expect(noAttributes.attributes, isEmpty);
    });

    test('parseHexColor accepts #RRGGBB only', () {
      expect(parseHexColor('#D4AF37'), 0xFFD4AF37);
      expect(parseHexColor('0ea5e9'), 0xFF0EA5E9);
      expect(parseHexColor('#FFF'), isNull);
      expect(parseHexColor('#GGGGGG'), isNull);
      expect(parseHexColor(123), isNull);
    });

    test('TestingState parses wallet, achievements and the clock', () {
      final state = TestingState.fromJson(_stateJson());

      expect(state.accountId, 'acc-1');
      expect(state.clockOffsetDays, 7.5);
      expect(state.economyNow, DateTime.utc(2026, 10, 21, 12));
      expect(state.wallet.items, hasLength(3));

      final achievement = state.achievements.single;
      expect(achievement.completed, isTrue);
      expect(achievement.completedAt, DateTime.utc(2026, 10, 14, 9));
      expect(achievement.rewards, hasLength(3));
      expect(achievement.rewards[0].type, AchievementReward.typeItem);
      expect(achievement.rewards[0].itemCode, 'FRAME_STUB_GOLD');
      expect(achievement.rewards[1].days, 3);
      expect(
        achievement.rewards.map(describeReward).toList(),
        ['FRAME_STUB_GOLD ×1', 'премиум +3 дн.', 'SOMETHING_NEW'],
      );
    });

    test('TestingState defaults when fields are missing', () {
      final state = TestingState.fromJson(const <String, dynamic>{});
      expect(state.clockOffsetDays, 0);
      expect(state.economyNow, isNull);
      expect(state.wallet.items, isEmpty);
      expect(state.achievements, isEmpty);
    });

    test('Account.testAccount defaults to false', () {
      expect(
        Account.fromJson({'id': 'a', 'testAccount': true}).testAccount,
        isTrue,
      );
      expect(Account.fromJson({'id': 'a'}).testAccount, isFalse);
      expect(
        Account.fromJson({'id': 'a', 'testAccount': 'true'}).testAccount,
        isFalse,
      );
    });

    test('formatDays', () {
      expect(formatDays(7), '7');
      expect(formatDays(0.5), '0.5');
      expect(formatDays(1.25), '1.25');
    });
  });

  group('testing API calls', () {
    final cases = <({
      String name,
      Future<TestingState> Function(DatingApiClient api) call,
      String method,
      String path,
      Object? body,
    })>[
      (
        name: 'state',
        call: (api) => api.getTestingState(),
        method: 'GET',
        path: '/testing/state',
        body: null,
      ),
      (
        name: 'complete achievement',
        call: (api) => api.completeTestAchievement('STUB_COLLECTOR'),
        method: 'POST',
        path: '/testing/achievements/STUB_COLLECTOR/complete',
        body: null,
      ),
      (
        name: 'reset achievement',
        call: (api) => api.resetTestAchievement('STUB_COLLECTOR'),
        method: 'DELETE',
        path: '/testing/achievements/STUB_COLLECTOR',
        body: null,
      ),
      (
        name: 'grant item',
        call: (api) => api.grantTestItem('SUPERLIKE', 3),
        method: 'POST',
        path: '/testing/wallet/items/SUPERLIKE',
        body: {'quantity': 3},
      ),
      (
        name: 'set item balance',
        call: (api) => api.setTestItemBalance('SUPERLIKE', 0),
        method: 'PUT',
        path: '/testing/wallet/items/SUPERLIKE',
        body: {'quantity': 0},
      ),
      (
        name: 'set total item balance',
        call: (api) => api.setTestItemBalance('SUPERLIKE', 2, total: true),
        method: 'PUT',
        path: '/testing/wallet/items/SUPERLIKE',
        body: {'quantity': 2, 'total': true},
      ),
      (
        name: 'add premium days',
        call: (api) => api.addTestPremiumDays(30),
        method: 'POST',
        path: '/testing/premium',
        body: {'days': 30},
      ),
      (
        name: 'remove premium',
        call: (api) => api.removeTestPremium(),
        method: 'DELETE',
        path: '/testing/premium',
        body: null,
      ),
      (
        name: 'advance clock',
        call: (api) => api.advanceTestClock(7),
        method: 'POST',
        path: '/testing/clock/advance',
        body: {'days': 7},
      ),
      (
        name: 'reset clock',
        call: (api) => api.resetTestClock(),
        method: 'DELETE',
        path: '/testing/clock',
        body: null,
      ),
    ];

    for (final c in cases) {
      test('${c.name} → ${c.method} ${c.path}', () async {
        final t = _testingClient();

        final state = await c.call(t.client);

        expect(t.requests, hasLength(1));
        final request = t.requests.single;
        expect(request.method, c.method);
        expect(request.url.toString(), 'http://api.test/api/v1${c.path}');
        expect(request.headers['Authorization'], 'Bearer tkn');
        expect(_body(request), c.body);
        expect(state.accountId, 'acc-1');
      });
    }

    test('item codes are encoded as one path segment', () async {
      final t = _testingClient();
      await t.client.grantTestItem('A/B C', 1);
      expect(
        t.requests.single.url.toString(),
        'http://api.test/api/v1/testing/wallet/items/A%2FB%20C',
      );
    });

    test('403 TEST_ACCOUNT_REQUIRED surfaces as ApiException', () async {
      final client = DatingApiClient(
        baseUrl: 'http://api.test/api/v1',
        httpClient: MockClient(
          (_) async => _json(
            {
              'code': 'TEST_ACCOUNT_REQUIRED',
              'message': 'Доступно только тестовым аккаунтам',
              'traceId': 't',
            },
            403,
          ),
        ),
      );
      client.token = 'tkn';

      await expectLater(
        client.getTestingState(),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 403)
              .having((e) => e.code, 'code', 'TEST_ACCOUNT_REQUIRED'),
        ),
      );
    });
  });

  group('account, wallet and cosmetics calls', () {
    late List<http.Request> requests;
    late Object? responseBody;
    late DatingApiClient client;

    setUp(() {
      requests = <http.Request>[];
      responseBody = _walletJson();
      client = DatingApiClient(
        baseUrl: 'http://api.test/api/v1',
        httpClient: MockClient((request) async {
          requests.add(request);
          return _json(responseBody, 200);
        }),
      );
      client.token = 'tkn';
    });

    test('getMe → GET /me', () async {
      responseBody = {
        'id': 'a1',
        'phoneMasked': '+7 900 ***-**-01',
        'status': 'ACTIVE',
        'createdAt': '2026-10-01T00:00:00Z',
        'testAccount': true,
      };
      final account = await client.getMe();
      expect(requests.single.method, 'GET');
      expect(requests.single.url.path, '/api/v1/me');
      expect(account.testAccount, isTrue);
    });

    test('getWallet → GET /me/wallet', () async {
      final wallet = await client.getWallet();
      expect(requests.single.method, 'GET');
      expect(requests.single.url.path, '/api/v1/me/wallet');
      expect(wallet.items, hasLength(3));
    });

    test('equipCosmetic → PUT /me/cosmetics/equipped/{slot}', () async {
      final wallet =
          await client.equipCosmetic('PHOTO_FRAME', 'FRAME_STUB_NEON');
      final request = requests.single;
      expect(request.method, 'PUT');
      expect(request.url.path, '/api/v1/me/cosmetics/equipped/PHOTO_FRAME');
      expect(_body(request), {'itemCode': 'FRAME_STUB_NEON'});
      expect(wallet.equipped['PHOTO_FRAME'], 'FRAME_STUB_NEON');
    });

    test('unequipCosmetic → DELETE /me/cosmetics/equipped/{slot}', () async {
      await client.unequipCosmetic('PROFILE_STATUS');
      final request = requests.single;
      expect(request.method, 'DELETE');
      expect(
        request.url.path,
        '/api/v1/me/cosmetics/equipped/PROFILE_STATUS',
      );
      expect(_body(request), isNull);
    });

    test('getAchievements → GET /me/achievements', () async {
      responseBody = _stateJson()['achievements'];
      final achievements = await client.getAchievements();
      expect(requests.single.method, 'GET');
      expect(requests.single.url.path, '/api/v1/me/achievements');
      expect(achievements.single.code, 'STUB_PROFILE_PUBLISHED');
    });
  });
}
