import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/models/discovery_filters.dart';
import 'package:dating_mobile/core/models/public_profile.dart';
import 'package:dating_mobile/core/storage/token_storage.dart';
import 'package:dating_mobile/features/auth/session_controller.dart';
import 'package:dating_mobile/features/discovery/discovery_filters_screen.dart';
import 'package:dating_mobile/features/discovery/profile_card.dart';
import 'package:dating_mobile/features/exclusion_words/exclusion_words_screen.dart';
import 'package:dating_mobile/features/premium/premium_badge.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

const _goals = [
  {'code': 'SERIOUS_RELATIONSHIP', 'label': 'Серьёзные отношения', 'sortOrder': 1},
  {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
  {'code': 'CHAT', 'label': 'Общение', 'sortOrder': 5},
];

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

http.Response _json(Object? body, [int status = 200]) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// Fake backend for the filters screen.
http.Response Function(http.Request) _backend({
  required bool premium,
  required List<String> excluded,
  required bool excludedActive,
  int putStatus = 200,
}) {
  return (request) {
    final path = request.url.path;
    if (path.endsWith('/goals')) return _json(_goals);
    if (path.endsWith('/me/wallet')) {
      return _json({
        'premium': {'active': premium, 'until': null},
        'premiumFeatures': ['ADVANCED_FILTERS', 'EXCLUSION_WORDS'],
        'items': <Object>[],
        'equipped': <String, String>{},
      });
    }
    if (path.endsWith('/me/excluded-goals')) {
      if (request.method == 'PUT') {
        if (putStatus != 200) {
          return _json(
            {
              'code': 'PREMIUM_REQUIRED',
              'message': 'Эта функция доступна с премиумом',
              'traceId': 't',
              'details': {'feature': 'ADVANCED_FILTERS'},
            },
            putStatus,
          );
        }
        final body = jsonDecode(request.body) as Map<String, dynamic>;
        return _json({'goalCodes': body['goalCodes'], 'active': true});
      }
      return _json({'goalCodes': excluded, 'active': excludedActive});
    }
    return _json({'code': 'NOT_FOUND', 'message': 'Не найдено'}, 404);
  };
}

/// Opens the filters screen from a host page; [result] receives the pop value.
Future<void> _pumpFilters(
  WidgetTester tester,
  SessionController session, {
  required void Function(DiscoveryFilters? result) onResult,
  DiscoveryFilters initial = DiscoveryFilters.none,
}) async {
  // Tall surface so the whole form fits without scrolling.
  tester.view.physicalSize = const Size(800, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(
    MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: TextButton(
              onPressed: () async {
                final result =
                    await Navigator.of(context).push<DiscoveryFilters>(
                  MaterialPageRoute<DiscoveryFilters>(
                    builder: (_) => DiscoveryFiltersScreen(
                      session: session,
                      initial: initial,
                    ),
                  ),
                );
                onResult(result);
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

FilterChip _chip(WidgetTester tester, String label, int index) => tester
    .widgetList<FilterChip>(
      find.widgetWithText(FilterChip, label),
    )
    .elementAt(index);

void main() {
  testWidgets('ProfileCard shows height, goals and the premium badge',
      (tester) async {
    final profile = PublicProfile.fromJson({
      'id': 'p1',
      'displayName': 'Анна',
      'age': 27,
      'city': 'Москва',
      'heightCm': 168,
      'goals': [
        {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
        {'code': 'SERIOUS_RELATIONSHIP', 'label': 'Серьёзные', 'sortOrder': 1},
      ],
      'premium': true,
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: ProfileCard(profile: profile)),
        ),
      ),
    );

    expect(find.text('Москва · 168 см'), findsOneWidget);
    expect(find.text('Ищу: Серьёзные, Семья'), findsOneWidget);
    expect(find.byType(PremiumBadge), findsOneWidget);
    expect(find.text('Премиум'), findsOneWidget);
  });

  testWidgets('ProfileCard without premium has no badge', (tester) async {
    final profile = PublicProfile.fromJson({'id': 'p1', 'displayName': 'Олег'});

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: ProfileCard(profile: profile)),
        ),
      ),
    );

    expect(find.byType(PremiumBadge), findsNothing);
    expect(find.textContaining('Ищу:'), findsNothing);
  });

  testWidgets(
      'filters without premium: advanced section disabled, '
      'saved goals marked as not applied, locked values are kept as is',
      (tester) async {
    final requests = <http.Request>[];
    final session = _session(
      requests,
      _backend(premium: false, excluded: ['CHAT'], excludedActive: false),
    );
    DiscoveryFilters? result;

    await _pumpFilters(
      tester,
      session,
      initial: const DiscoveryFilters(minHeight: 170, goalCodes: ['FAMILY']),
      onResult: (value) => result = value,
    );

    expect(find.text('Расширенный поиск · Премиум'), findsOneWidget);
    expect(find.text('Доступно с премиумом'), findsNWidgets(2));
    expect(
      find.text('Сохранено, но не применяется без премиума'),
      findsOneWidget,
    );
    // "Ищу цели" chips come first, "Скрывать цели" second.
    expect(_chip(tester, 'Семья', 0).onSelected, isNull);
    expect(_chip(tester, 'Общение', 1).selected, isTrue);
    expect(_chip(tester, 'Общение', 1).onSelected, isNull);

    await tester.enterText(find.widgetWithText(TextField, 'Город'), 'Казань');
    await tester.tap(find.text('Применить'));
    await tester.pumpAndSettle();

    expect(requests.where((r) => r.method == 'PUT'), isEmpty);
    expect(result, isNotNull);
    expect(result!.city, 'Казань');
    // Kept (not sent by the feed while premium is locked).
    expect(result!.minHeight, 170);
    expect(result!.goalCodes, ['FAMILY']);
  });

  testWidgets('filters with premium: saves excluded goals and applies goals',
      (tester) async {
    final requests = <http.Request>[];
    final session = _session(
      requests,
      _backend(premium: true, excluded: <String>[], excludedActive: true),
    );
    DiscoveryFilters? result;

    await _pumpFilters(tester, session, onResult: (value) => result = value);

    expect(find.text('Доступно с премиумом'), findsNothing);

    await tester.tap(find.widgetWithText(FilterChip, 'Семья').first);
    await tester.tap(find.widgetWithText(FilterChip, 'Общение').last);
    await tester.pump();
    await tester.tap(find.text('Применить'));
    await tester.pumpAndSettle();

    final put = requests.singleWhere((r) => r.method == 'PUT');
    expect(put.url.path, '/api/v1/me/excluded-goals');
    expect(jsonDecode(put.body), {
      'goalCodes': ['CHAT'],
    });
    expect(result, isNotNull);
    expect(result!.goalCodes, ['FAMILY']);
    // Sliders untouched = no height bounds.
    expect(result!.minHeight, isNull);
    expect(result!.maxHeight, isNull);
  });

  testWidgets('403 PREMIUM_REQUIRED on save locks the section and stays',
      (tester) async {
    final requests = <http.Request>[];
    final session = _session(
      requests,
      _backend(
        premium: true,
        excluded: <String>[],
        excludedActive: true,
        putStatus: 403,
      ),
    );
    var popped = false;

    await _pumpFilters(tester, session, onResult: (_) => popped = true);

    await tester.tap(find.widgetWithText(FilterChip, 'Общение').last);
    await tester.pump();
    await tester.tap(find.text('Применить'));
    await tester.pumpAndSettle();

    expect(popped, isFalse);
    expect(find.textContaining('Расширенный поиск доступен с премиумом'),
        findsOneWidget);
    expect(_chip(tester, 'Общение', 1).selected, isFalse);
    expect(_chip(tester, 'Общение', 1).onSelected, isNull);

    // Second tap applies the free filters without another PUT.
    await tester.tap(find.text('Применить'));
    await tester.pumpAndSettle();
    expect(popped, isTrue);
    expect(requests.where((r) => r.method == 'PUT'), hasLength(1));
  });

  testWidgets('exclusion words: inactive list is read-only', (tester) async {
    final requests = <http.Request>[];
    final session = _session(
      requests,
      (_) => _json({
        'words': ['курю'],
        'maxWords': 20,
        'active': false,
      }),
    );

    await tester.pumpWidget(
      MaterialApp(home: ExclusionWordsScreen(session: session)),
    );
    await tester.pumpAndSettle();

    expect(
      find.textContaining('не применяется без премиума'),
      findsOneWidget,
    );
    final save = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Сохранить'),
    );
    expect(save.onPressed, isNull);
    final chip = tester.widget<InputChip>(find.byType(InputChip));
    expect(chip.onDeleted, isNull);
  });

  testWidgets('exclusion words: 403 PREMIUM_REQUIRED on save -> read-only',
      (tester) async {
    final requests = <http.Request>[];
    final session = _session(requests, (request) {
      if (request.method == 'PUT') {
        return _json(
          {
            'code': 'PREMIUM_REQUIRED',
            'message': 'Эта функция доступна с премиумом',
            'traceId': 't',
            'details': {'feature': 'EXCLUSION_WORDS'},
          },
          403,
        );
      }
      return _json({'words': <String>[], 'maxWords': 20, 'active': true});
    });

    await tester.pumpWidget(
      MaterialApp(home: ExclusionWordsScreen(session: session)),
    );
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'курю');
    await tester.tap(find.text('Сохранить'));
    await tester.pumpAndSettle();

    expect(find.text('Эта функция доступна с премиумом'), findsOneWidget);
    final save = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Сохранить'),
    );
    expect(save.onPressed, isNull);
  });
}
