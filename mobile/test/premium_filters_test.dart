import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/api/api_exception.dart';
import 'package:dating_mobile/core/models/discovery_filters.dart';
import 'package:dating_mobile/core/models/excluded_goals.dart';
import 'package:dating_mobile/core/models/exclusion_words.dart';
import 'package:dating_mobile/core/models/goal.dart';
import 'package:dating_mobile/core/models/public_profile.dart';
import 'package:dating_mobile/core/models/wallet.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response _json(Object? body, [int status = 200]) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

Map<String, dynamic> _emptyPage() => {
      'items': <Object>[],
      'page': 0,
      'limit': 20,
      'totalElements': 0,
      'totalPages': 0,
    };

/// Client that records requests and answers every one with [respond].
({DatingApiClient client, List<http.Request> requests}) _client(
  http.Response Function(http.Request request) respond,
) {
  final requests = <http.Request>[];
  final client = DatingApiClient(
    baseUrl: 'http://api.test/api/v1',
    httpClient: MockClient((request) async {
      requests.add(request);
      return respond(request);
    }),
  );
  client.token = 'tkn';
  return (client: client, requests: requests);
}

void main() {
  group('parsing', () {
    test('Goal.parseList sorts by sortOrder and drops entries without code',
        () {
      final goals = Goal.parseList([
        {'code': 'FRIENDSHIP', 'label': 'Дружба', 'sortOrder': 4},
        {'code': 'SERIOUS_RELATIONSHIP', 'label': 'Серьёзные', 'sortOrder': 1},
        {'label': 'Без кода', 'sortOrder': 0},
        'not-an-object',
        {'code': 'CHAT', 'sortOrder': 5.0},
      ]);

      expect(goals.map((g) => g.code), [
        'SERIOUS_RELATIONSHIP',
        'FRIENDSHIP',
        'CHAT',
      ]);
      expect(goals.last.displayLabel, 'CHAT');
      expect(Goal.parseList(null), isEmpty);
    });

    test('PublicProfile parses heightCm, goals and premium', () {
      final profile = PublicProfile.fromJson({
        'id': 'p1',
        'displayName': 'Анна',
        'heightCm': 172.0,
        'goals': [
          {'code': 'FRIENDSHIP', 'label': 'Дружба', 'sortOrder': 4},
          {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
        ],
        'premium': true,
      });

      expect(profile.heightCm, 172);
      expect(profile.goals.map((g) => g.label), ['Семья', 'Дружба']);
      expect(profile.premium, isTrue);
    });

    test('PublicProfile defaults when the new fields are missing or broken',
        () {
      final missing = PublicProfile.fromJson({'id': 'p1'});
      expect(missing.heightCm, isNull);
      expect(missing.goals, isEmpty);
      expect(missing.premium, isFalse);

      final broken = PublicProfile.fromJson({
        'id': 'p1',
        'heightCm': '180',
        'goals': 'FAMILY',
        'premium': 'true',
      });
      expect(broken.heightCm, isNull);
      expect(broken.goals, isEmpty);
      expect(broken.premium, isFalse);
    });

    test('ExclusionWords.active: parsed, missing or broken means true', () {
      expect(
        ExclusionWords.fromJson({
          'words': ['курю'],
          'maxWords': 20,
          'active': false,
        }).active,
        isFalse,
      );
      expect(ExclusionWords.fromJson({'words': <String>[]}).active, isTrue);
      expect(
        ExclusionWords.fromJson({'words': <String>[], 'active': 'no'}).active,
        isTrue,
      );
    });

    test('ExcludedGoals parses goalCodes and active defensively', () {
      final excluded = ExcludedGoals.fromJson({
        'goalCodes': ['CHAT', ' ', 7, 'NOT_SURE'],
        'active': false,
      });
      expect(excluded.goalCodes, ['CHAT', 'NOT_SURE']);
      expect(excluded.active, isFalse);

      final empty = ExcludedGoals.fromJson(const {});
      expect(empty.goalCodes, isEmpty);
      expect(empty.active, isTrue);
    });

    test('Wallet.isFeatureLocked needs a premium-only feature and no premium',
        () {
      Wallet wallet({required bool active}) => Wallet.fromJson({
            'premium': {'active': active, 'until': null},
            'premiumFeatures': ['ADVANCED_FILTERS'],
          });

      expect(
        wallet(active: false)
            .isFeatureLocked(PremiumFeatureCodes.advancedFilters),
        isTrue,
      );
      expect(
        wallet(active: true)
            .isFeatureLocked(PremiumFeatureCodes.advancedFilters),
        isFalse,
      );
      // Not listed in premiumFeatures = free.
      expect(
        wallet(active: false)
            .isFeatureLocked(PremiumFeatureCodes.exclusionWords),
        isFalse,
      );
    });

    test('ApiException keeps details of 403 PREMIUM_REQUIRED', () {
      final error = ApiException.fromResponse(403, {
        'code': 'PREMIUM_REQUIRED',
        'message': 'Эта функция доступна с премиумом',
        'traceId': 't-1',
        'details': {'feature': 'ADVANCED_FILTERS'},
      });

      expect(error.isPremiumRequired, isTrue);
      expect(error.premiumFeature, 'ADVANCED_FILTERS');
      expect(error.details, {'feature': 'ADVANCED_FILTERS'});
      expect(error.message, 'Эта функция доступна с премиумом');

      final other = ApiException.fromResponse(400, {
        'code': 'VALIDATION_ERROR',
        'details': 'oops',
      });
      expect(other.isPremiumRequired, isFalse);
      expect(other.details, isEmpty);
      expect(other.premiumFeature, isNull);
    });

    test('DiscoveryFilters: advanced detection and withoutAdvanced', () {
      expect(DiscoveryFilters.none.isEmpty, isTrue);
      expect(const DiscoveryFilters(city: '  ').isEmpty, isTrue);

      const filters = DiscoveryFilters(
        city: 'Москва',
        minAge: 25,
        maxHeight: 190,
        goalCodes: ['FAMILY'],
      );
      expect(filters.hasBasic, isTrue);
      expect(filters.hasAdvanced, isTrue);

      final free = filters.withoutAdvanced();
      expect(free.hasAdvanced, isFalse);
      expect(free.city, 'Москва');
      expect(free.minAge, 25);
      expect(free.maxHeight, isNull);
      expect(free.goalCodes, isEmpty);
    });
  });

  group('request building', () {
    test('discovery sends basic and advanced query params', () async {
      final t = _client((_) => _json(_emptyPage()));

      await t.client.getDiscoveryProfiles(
        page: 2,
        limit: 10,
        city: ' Казань ',
        minAge: 25,
        maxAge: 35,
        tagCodes: ['SLOW_PACE', 'CALLS_OK'],
        tagLogic: 'AND',
        minHeight: 160,
        maxHeight: 185,
        goalCodes: ['SERIOUS_RELATIONSHIP', 'FAMILY'],
      );

      final url = t.requests.single.url;
      expect(t.requests.single.method, 'GET');
      expect(url.path, '/api/v1/discovery/profiles');
      expect(url.queryParameters, {
        'page': '2',
        'limit': '10',
        'city': 'Казань',
        'minAge': '25',
        'maxAge': '35',
        'tagCodes': 'SLOW_PACE,CALLS_OK',
        'tagLogic': 'AND',
        'minHeight': '160',
        'maxHeight': '185',
        'goalCodes': 'SERIOUS_RELATIONSHIP,FAMILY',
      });
      expect(t.requests.single.headers['Authorization'], 'Bearer tkn');
    });

    test('discovery omits unset filters', () async {
      final t = _client((_) => _json(_emptyPage()));

      await t.client.getDiscoveryProfiles(city: '   ');

      expect(t.requests.single.url.queryParameters, {
        'page': '0',
        'limit': '20',
      });
    });

    test('getDiscoveryPage maps DiscoveryFilters to query params', () async {
      final t = _client((_) => _json(_emptyPage()));

      await t.client.getDiscoveryPage(
        const DiscoveryFilters(
          city: 'Москва',
          maxAge: 40,
          minHeight: 170,
          goalCodes: ['CHAT'],
        ),
        page: 1,
      );

      expect(t.requests.single.url.queryParameters, {
        'page': '1',
        'limit': '20',
        'city': 'Москва',
        'maxAge': '40',
        'minHeight': '170',
        'goalCodes': 'CHAT',
      });
    });

    test('getGoals is public GET /goals', () async {
      final t = _client(
        (_) => _json([
          {'code': 'DATING', 'label': 'Свидания', 'sortOrder': 3},
          {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
        ]),
      );

      final goals = await t.client.getGoals();

      final request = t.requests.single;
      expect(request.method, 'GET');
      expect(request.url.toString(), 'http://api.test/api/v1/goals');
      expect(request.headers.containsKey('Authorization'), isFalse);
      expect(goals.map((g) => g.code), ['FAMILY', 'DATING']);
    });

    test('getExcludedGoals sends GET with the bearer token', () async {
      final t = _client(
        (_) => _json({
          'goalCodes': ['CHAT'],
          'active': false,
        }),
      );

      final excluded = await t.client.getExcludedGoals();

      final request = t.requests.single;
      expect(request.method, 'GET');
      expect(
        request.url.toString(),
        'http://api.test/api/v1/me/excluded-goals',
      );
      expect(request.headers['Authorization'], 'Bearer tkn');
      expect(excluded.goalCodes, ['CHAT']);
      expect(excluded.active, isFalse);
    });

    test('updateExcludedGoals sends PUT with the full list', () async {
      final t = _client((request) => _json(jsonDecode(request.body)
        ..['active'] = true));

      final saved =
          await t.client.updateExcludedGoals(['CHAT', 'NOT_SURE']);

      final request = t.requests.single;
      expect(request.method, 'PUT');
      expect(request.url.path, '/api/v1/me/excluded-goals');
      expect(request.headers['Content-Type'], startsWith('application/json'));
      expect(jsonDecode(request.body), {
        'goalCodes': ['CHAT', 'NOT_SURE'],
      });
      expect(saved.goalCodes, ['CHAT', 'NOT_SURE']);
      expect(saved.active, isTrue);
    });

    test('updateExcludedGoals with an empty list still sends the body',
        () async {
      final t = _client(
        (_) => _json({'goalCodes': <String>[], 'active': true}),
      );

      await t.client.updateExcludedGoals(const []);

      expect(jsonDecode(t.requests.single.body), {'goalCodes': <String>[]});
    });

    test('403 PREMIUM_REQUIRED on PUT surfaces with details.feature',
        () async {
      final t = _client(
        (_) => _json(
          {
            'code': 'PREMIUM_REQUIRED',
            'message': 'Эта функция доступна с премиумом',
            'traceId': 't',
            'details': {'feature': 'ADVANCED_FILTERS'},
          },
          403,
        ),
      );

      await expectLater(
        t.client.updateExcludedGoals(['CHAT']),
        throwsA(
          isA<ApiException>()
              .having((e) => e.statusCode, 'statusCode', 403)
              .having((e) => e.isPremiumRequired, 'isPremiumRequired', true)
              .having((e) => e.premiumFeature, 'feature', 'ADVANCED_FILTERS'),
        ),
      );
      // A 403 must not drop the session.
      expect(t.client.token, 'tkn');
    });
  });
}
