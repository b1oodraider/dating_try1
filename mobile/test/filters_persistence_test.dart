import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/models/discovery_filters.dart';
import 'package:dating_mobile/core/storage/discovery_filters_store.dart';
import 'package:dating_mobile/core/storage/token_storage.dart';
import 'package:dating_mobile/features/auth/session_controller.dart';
import 'package:dating_mobile/features/discovery/discovery_screen.dart';
import 'package:dating_mobile/features/profile/height_goals_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

http.Response _json(Object? body, [int status = 200]) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

http.Response _noContent() => http.Response('', 204);

SessionController _session(
  List<http.Request> requests,
  http.Response Function(http.Request request) respond,
) {
  final api = DatingApiClient(
    baseUrl: 'http://api.test/api/v1',
    httpClient: MockClient((request) async {
      requests.add(request);
      return respond(request);
    }),
  );
  api.token = 'tkn';
  return SessionController(api: api, storage: TokenStorage());
}

const _full = DiscoveryFilters(
  city: 'Москва',
  minAge: 25,
  maxAge: 35,
  minHeight: 165,
  maxHeight: 190,
  goalCodes: ['FAMILY', 'CHAT'],
);

void main() {
  setUp(() => SharedPreferences.setMockInitialValues(<String, Object>{}));

  group('DiscoveryFiltersStore', () {
    test('saves and loads per account', () async {
      final store = DiscoveryFiltersStore();

      await store.save('acc-1', _full);
      await store.save('acc-2', const DiscoveryFilters(city: 'Казань'));

      final first = await store.load('acc-1');
      expect(first.city, 'Москва');
      expect(first.minAge, 25);
      expect(first.maxAge, 35);
      expect(first.minHeight, 165);
      expect(first.maxHeight, 190);
      expect(first.goalCodes, ['FAMILY', 'CHAT']);

      final second = await store.load('acc-2');
      expect(second.city, 'Казань');
      expect(second.hasAdvanced, isFalse);

      expect((await store.load('acc-3')).isEmpty, isTrue);

      final prefs = await SharedPreferences.getInstance();
      expect(
        prefs.getKeys(),
        {'discovery_filters.v1.acc-1', 'discovery_filters.v1.acc-2'},
      );
    });

    test('saving empty filters removes the key', () async {
      final store = DiscoveryFiltersStore();
      await store.save('acc-1', _full);
      await store.save('acc-1', DiscoveryFilters.none);

      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getKeys(), isEmpty);
    });

    test('corrupt data falls back to empty filters', () async {
      SharedPreferences.setMockInitialValues({
        'discovery_filters.v1.a': '{not json',
        'discovery_filters.v1.b': '[1,2]',
        'discovery_filters.v1.c': jsonEncode({
          'city': 42,
          'minAge': 10, // out of range
          'maxAge': '30', // wrong type
          'minHeight': 200, // inverted pair: both dropped
          'maxHeight': 150,
          'goalCodes': ['FAMILY', 7, ' ', 'FAMILY'],
        }),
      });
      final store = DiscoveryFiltersStore();

      expect((await store.load('a')).isEmpty, isTrue);
      expect((await store.load('b')).isEmpty, isTrue);

      final c = await store.load('c');
      expect(c.city, isNull);
      expect(c.minAge, isNull);
      expect(c.maxAge, isNull);
      expect(c.minHeight, isNull);
      expect(c.maxHeight, isNull);
      expect(c.goalCodes, ['FAMILY']);
    });

    test('storage failures load as empty and never throw', () async {
      final store = DiscoveryFiltersStore(
        preferences: () => Future<SharedPreferences>.error(
          StateError('storage unavailable'),
        ),
      );

      expect((await store.load('acc-1')).isEmpty, isTrue);
      await store.save('acc-1', _full);
      await store.clearAll();
    });

    test('clearAll removes only saved filters', () async {
      SharedPreferences.setMockInitialValues({'other.key': 'keep'});
      final store = DiscoveryFiltersStore();
      await store.save('acc-1', _full);
      await store.save('acc-2', _full);

      await store.clearAll();

      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getKeys(), {'other.key'});
    });
  });

  group('session', () {
    test('signOut clears the saved filters', () async {
      final requests = <http.Request>[];
      final session = _session(requests, (_) => _noContent());
      await session.filtersStore.save('acc-1', _full);

      await session.signOut();

      expect((await session.filtersStore.load('acc-1')).isEmpty, isTrue);
      expect(requests.single.url.path, '/api/v1/auth/logout');
      expect(session.api.token, isNull);
    });

    test('deleteAccount sends DELETE /me, then clears the filters', () async {
      final requests = <http.Request>[];
      final session = _session(requests, (_) => _noContent());
      await session.filtersStore.save('acc-1', _full);

      await session.deleteAccount();

      expect(requests.single.method, 'DELETE');
      expect(requests.single.url.path, '/api/v1/me');
      expect((await session.filtersStore.load('acc-1')).isEmpty, isTrue);
      expect(session.api.token, isNull);
    });

    test('a failed deleteAccount keeps the session and the filters',
        () async {
      final requests = <http.Request>[];
      final session = _session(
        requests,
        (_) => _json({'code': 'INTERNAL_ERROR', 'message': 'Ошибка'}, 500),
      );
      await session.filtersStore.save('acc-1', _full);

      await expectLater(session.deleteAccount(), throwsA(anything));

      expect(session.api.token, 'tkn');
      expect((await session.filtersStore.load('acc-1')).city, 'Москва');
    });
  });

  group('PUT /me/profile', () {
    Map<String, dynamic> profile({int? heightCm}) => {
          'id': 'p1',
          'displayName': 'Анна',
          'heightCm': heightCm,
          'goals': <Object>[],
          'premium': false,
        };

    test('clearHeight sends only {clearHeight: true}', () async {
      final requests = <http.Request>[];
      final session = _session(requests, (_) => _json(profile()));

      final saved = await session.api.updateMyProfile(clearHeight: true);

      final request = requests.single;
      expect(request.method, 'PUT');
      expect(request.url.path, '/api/v1/me/profile');
      expect(jsonDecode(request.body), {'clearHeight': true});
      expect(saved.heightCm, isNull);
    });

    test('heightCm and an empty goal list are sent as given', () async {
      final requests = <http.Request>[];
      final session = _session(requests, (_) => _json(profile(heightCm: 181)));

      final saved =
          await session.api.updateMyProfile(heightCm: 181, goalCodes: []);

      expect(jsonDecode(requests.single.body), {
        'heightCm': 181,
        'goalCodes': <String>[],
      });
      expect(saved.heightCm, 181);
    });

    test('clearHeight together with heightCm is rejected locally', () async {
      final requests = <http.Request>[];
      final session = _session(requests, (_) => _json(profile()));

      await expectLater(
        session.api.updateMyProfile(heightCm: 170, clearHeight: true),
        throwsArgumentError,
      );
      expect(requests, isEmpty);
    });
  });

  testWidgets('height editor removes the height via clearHeight',
      (tester) async {
    final requests = <http.Request>[];
    final session = _session(requests, (request) {
      final path = request.url.path;
      if (path.endsWith('/goals')) {
        return _json([
          {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
        ]);
      }
      if (request.method == 'PUT') {
        return _json({
          'id': 'p1',
          'heightCm': null,
          'goals': [
            {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
          ],
        });
      }
      return _json({
        'id': 'p1',
        'heightCm': 175,
        'goals': [
          {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
        ],
      });
    });

    await tester.pumpWidget(
      MaterialApp(home: HeightGoalsScreen(session: session)),
    );
    await tester.pumpAndSettle();

    expect(find.text('175 см'), findsOneWidget);
    await tester.tap(find.byType(Switch));
    await tester.pump();
    expect(find.text('Рост не указан'), findsOneWidget);

    await tester.tap(find.text('Сохранить'));
    await tester.pumpAndSettle();

    final put = requests.singleWhere((r) => r.method == 'PUT');
    expect(jsonDecode(put.body), {'clearHeight': true});
  });

  group('discovery feed', () {
    http.Response Function(http.Request) backend({required bool premium}) {
      return (request) {
        final path = request.url.path;
        if (path.endsWith('/me')) {
          return _json({'id': 'acc-1', 'testAccount': false});
        }
        if (path.endsWith('/me/wallet')) {
          return _json({
            'premium': {'active': premium, 'until': null},
            'premiumFeatures': ['ADVANCED_FILTERS'],
          });
        }
        return _json({
          'items': <Object>[],
          'page': 0,
          'limit': 20,
          'totalElements': 0,
          'totalPages': 0,
        });
      };
    }

    Future<List<http.Request>> pumpFeed(
      WidgetTester tester, {
      required bool premium,
    }) async {
      SharedPreferences.setMockInitialValues({
        DiscoveryFiltersStore.keyFor('acc-1'):
            DiscoveryFiltersStore.encode(_full),
      });
      final requests = <http.Request>[];
      final session = _session(requests, backend(premium: premium));
      await tester.pumpWidget(
        MaterialApp(home: DiscoveryScreen(session: session)),
      );
      await tester.pumpAndSettle();
      return requests;
    }

    testWidgets('saved filters apply to the first request (premium)',
        (tester) async {
      final requests = await pumpFeed(tester, premium: true);

      final feed = requests
          .where((r) => r.url.path.endsWith('/discovery/profiles'))
          .toList();
      expect(feed, hasLength(1));
      expect(feed.single.url.queryParameters, {
        'page': '0',
        'limit': '20',
        'city': 'Москва',
        'minAge': '25',
        'maxAge': '35',
        'minHeight': '165',
        'maxHeight': '190',
        'goalCodes': 'FAMILY,CHAT',
      });
      // Only the filters entry point is about filtering.
      expect(find.byTooltip('Фильтры'), findsOneWidget);
      expect(find.byTooltip('Слова-исключения'), findsNothing);
    });

    testWidgets('without premium saved height and goals are kept, not sent',
        (tester) async {
      final requests = await pumpFeed(tester, premium: false);

      final feed = requests
          .singleWhere((r) => r.url.path.endsWith('/discovery/profiles'));
      expect(feed.url.queryParameters, {
        'page': '0',
        'limit': '20',
        'city': 'Москва',
        'minAge': '25',
        'maxAge': '35',
      });
      final prefs = await SharedPreferences.getInstance();
      expect(
        prefs.getString(DiscoveryFiltersStore.keyFor('acc-1')),
        DiscoveryFiltersStore.encode(_full),
      );
    });
  });
}
