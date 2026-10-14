import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/models/public_profile.dart';
import 'package:dating_mobile/core/storage/token_storage.dart';
import 'package:dating_mobile/features/auth/session_controller.dart';
import 'package:dating_mobile/features/collection/collection_screen.dart';
import 'package:dating_mobile/features/cosmetics/cosmetic_visuals.dart';
import 'package:dating_mobile/features/discovery/profile_card.dart';
import 'package:dating_mobile/features/testing/test_panel_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Map<String, dynamic> _wallet({required bool frameEquipped}) => {
      'premium': {'active': false, 'until': null},
      'premiumFeatures': <String>[],
      'items': [
        {
          'code': 'SUPERLIKE',
          'kind': 'CONSUMABLE',
          'category': 'SUPERLIKE',
          'title': 'Суперлайк',
          'attributes': {'icon': 'star'},
          'quantity': 5,
          'periodicQuantity': 5,
        },
        {
          'code': 'FRAME_STUB_GOLD',
          'kind': 'COSMETIC',
          'category': 'PHOTO_FRAME',
          'title': 'Золотая рамка (тест)',
          'rarity': 'EPIC',
          'equipSlot': 'PHOTO_FRAME',
          'attributes': {'borderColor': '#D4AF37', 'borderWidth': 4},
          'quantity': 1,
          'periodicQuantity': 0,
        },
        {
          'code': 'STICKERS_STUB_CATS',
          'kind': 'COSMETIC',
          'category': 'STICKER_PACK',
          'title': 'Котики (тест)',
          'rarity': 'COMMON',
          'attributes': {
            'emoji': '🐱',
            'stickers': ['cat_wave'],
          },
          'quantity': 1,
          'periodicQuantity': 0,
        },
      ],
      'equipped': frameEquipped
          ? {'PHOTO_FRAME': 'FRAME_STUB_GOLD'}
          : <String, String>{},
    };

SessionController _session(
  List<http.Request> requests,
  Object? Function(http.Request request) respond,
) {
  final api = DatingApiClient(
    baseUrl: 'http://api.test/api/v1',
    httpClient: MockClient((request) async {
      requests.add(request);
      return http.Response(
        jsonEncode(respond(request)),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    }),
  );
  api.token = 'tkn';
  return SessionController(api: api, storage: TokenStorage());
}

void main() {
  testWidgets('ProfileCard draws the photo frame and the status chip',
      (tester) async {
    final profile = PublicProfile.fromJson({
      'id': 'p1',
      'displayName': 'Анна',
      'age': 27,
      'decorations': {
        'PHOTO_FRAME': {
          'code': 'FRAME_STUB_NEON',
          'attributes': {
            'borderColor': '#22D3EE',
            'borderWidth': 3,
            'glow': true,
          },
        },
        'PROFILE_STATUS': {
          'code': 'STATUS_STUB_BOOKWORM',
          'attributes': {
            'emoji': '📚',
            'label': 'Книжный червь',
            'color': '#7C3AED',
          },
        },
        'UNKNOWN_SLOT': {'code': 'X', 'attributes': {}},
      },
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: ProfileCard(profile: profile)),
        ),
      ),
    );

    expect(find.byType(FramedPhoto), findsOneWidget);
    expect(find.text('📚 Книжный червь'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Collection lists items and unequips via DELETE',
      (tester) async {
    final requests = <http.Request>[];
    final session = _session(
      requests,
      (request) => _wallet(frameEquipped: request.method == 'GET'),
    );

    await tester.pumpWidget(
      MaterialApp(home: CollectionScreen(session: session)),
    );
    await tester.pumpAndSettle();

    expect(find.text('Премиум не активен'), findsOneWidget);
    expect(find.text('еженедельных: 5'), findsOneWidget);
    expect(find.text('Рамки для фото'), findsOneWidget);
    expect(find.text('Стикерпаки'), findsOneWidget);
    expect(find.text('Стикеры: cat_wave'), findsOneWidget);

    await tester.tap(find.text('Снять'));
    await tester.pumpAndSettle();

    expect(requests.last.method, 'DELETE');
    expect(requests.last.url.path, '/api/v1/me/cosmetics/equipped/PHOTO_FRAME');
    expect(find.text('Надеть'), findsOneWidget);
  });

  testWidgets('Test panel shows the state and advances the clock',
      (tester) async {
    final requests = <http.Request>[];
    final session = _session(
      requests,
      (request) => {
        'accountId': 'acc-1',
        'clockOffsetDays': requests.length > 1 ? 7 : 0,
        'economyNow': '2026-10-14T12:00:00Z',
        'wallet': _wallet(frameEquipped: false),
        'achievements': [
          {
            'code': 'STUB_FIRST_PHOTO',
            'title': 'Первое фото (тест)',
            'completed': false,
            'rewards': [
              {'type': 'ITEM', 'itemCode': 'SUPERLIKE', 'quantity': 1},
            ],
          },
        ],
      },
    );

    await tester.pumpWidget(
      MaterialApp(home: TestPanelScreen(session: session)),
    );
    await tester.pumpAndSettle();

    expect(find.text('Сдвиг часов: нет'), findsOneWidget);

    await tester.tap(find.text('Следующая неделя'));
    await tester.pumpAndSettle();

    expect(requests.last.method, 'POST');
    expect(requests.last.url.path, '/api/v1/testing/clock/advance');
    expect(jsonDecode(requests.last.body), {'days': 7});
    expect(find.text('Сдвиг часов: +7 дн.'), findsOneWidget);

    final reward = find.text('Награда: SUPERLIKE ×1');
    await tester.scrollUntilVisible(
      reward,
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(reward, findsOneWidget);
    expect(find.text('Выполнить'), findsOneWidget);
  });
}
