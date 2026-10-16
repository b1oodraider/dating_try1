import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/api/api_exception.dart';
import 'package:dating_mobile/core/models/discovery_page.dart';
import 'package:dating_mobile/core/models/exclusion_words.dart';
import 'package:dating_mobile/features/exclusion_words/exclusion_word_rules.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response _json(Object body, int status) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

void main() {
  test('DiscoveryPage parses contract payload defensively', () {
    final page = DiscoveryPage.fromJson({
      'items': [
        {
          'id': 'p1',
          'displayName': 'Анна',
          'age': 27.0,
          'city': 'Москва',
          'aboutText': null,
          'dailyStatusText': 'Привет',
          'tags': [
            {
              'code': 'SLOW_PACE',
              'category': 'pace',
              'categoryLabel': 'Темп',
              'label': 'Неспешно',
              'description': 'Пишу без спешки',
              'sortOrder': 1,
            },
          ],
          'photos': [
            {
              'id': 'ph2',
              'url': '/api/v1/media/photos/ph2?exp=1&sig=b',
              'mimeType': 'image/jpeg',
              'sizeBytes': 1024,
              'sortOrder': 1,
              'isPrimary': false,
              'createdAt': '2026-01-01T00:00:00Z',
            },
            {
              'id': 'ph1',
              'url': '/api/v1/media/photos/ph1?exp=1&sig=a',
              'sortOrder': 0,
              'isPrimary': true,
            },
          ],
        },
        'not-an-object',
      ],
      'page': 0,
      'limit': 20,
      'totalElements': 21,
      'totalPages': 2,
    });

    expect(page.items, hasLength(1));
    final profile = page.items.single;
    expect(profile.age, 27);
    expect(profile.aboutText, isNull);
    expect(profile.photos.first.id, 'ph1');
    expect(profile.primaryPhoto?.id, 'ph1');
    expect(profile.tags.single.label, 'Неспешно');
    expect(page.hasMore, isTrue);
  });

  test('resolveMediaUrl prefixes the API origin and keeps the signature', () {
    final client = DatingApiClient(baseUrl: 'http://10.0.2.2:8080/api/v1/');
    expect(
      client.resolveMediaUrl('/api/v1/media/photos/x?exp=1&sig=ab'),
      'http://10.0.2.2:8080/api/v1/media/photos/x?exp=1&sig=ab',
    );
  });

  test('verifyOtp sends only challengeId and code', () async {
    late http.Request captured;
    final client = DatingApiClient(
      baseUrl: 'http://api.test/api/v1',
      httpClient: MockClient((request) async {
        captured = request;
        return _json(
          {'token': 'tkn', 'expiresAt': '2026-01-01T00:00:00Z', 'accountId': 'a1'},
          200,
        );
      }),
    );

    final auth = await client.verifyOtp(challengeId: 'c1', code: '123456');

    expect(auth.token, 'tkn');
    expect(captured.url.toString(), 'http://api.test/api/v1/auth/otp/verify');
    expect(jsonDecode(captured.body), {'challengeId': 'c1', 'code': '123456'});
  });

  test('error body is parsed into ApiException', () async {
    final client = DatingApiClient(
      baseUrl: 'http://api.test/api/v1',
      httpClient: MockClient(
        (_) async => _json(
          {
            'code': 'VALIDATION_ERROR',
            'message': 'Проверьте данные',
            'traceId': 't-1',
            'fieldErrors': {'phone': 'Неверный номер'},
          },
          400,
        ),
      ),
    );

    try {
      await client.requestOtp('123');
      fail('expected ApiException');
    } on ApiException catch (e) {
      expect(e.statusCode, 400);
      expect(e.code, 'VALIDATION_ERROR');
      expect(e.message, 'Проверьте данные');
      expect(e.traceId, 't-1');
      expect(describeError(e, field: 'phone'), 'Неверный номер');
    }
  });

  test('401 with the current token clears it and notifies', () async {
    var notified = 0;
    final client = DatingApiClient(
      baseUrl: 'http://api.test/api/v1',
      httpClient: MockClient(
        (_) async => _json(
          {'code': 'UNAUTHORIZED', 'message': 'Войдите снова', 'traceId': 't'},
          401,
        ),
      ),
    );
    client.token = 'old';
    client.onUnauthorized = () async {
      notified++;
    };

    await expectLater(
      client.getDiscoveryProfiles(),
      throwsA(
        isA<ApiException>()
            .having((e) => e.isUnauthorized, 'isUnauthorized', isTrue),
      ),
    );
    expect(notified, 1);
    expect(client.token, isNull);
  });

  group('exclusion words', () {
    test('ExclusionWords parses contract payload defensively', () {
      final parsed = ExclusionWords.fromJson({
        'words': ['курю', 42, null, '  ', 'алкоголь'],
        'maxWords': 20.0,
      });
      expect(parsed.words, ['курю', 'алкоголь']);
      expect(parsed.maxWords, 20);

      final empty = ExclusionWords.fromJson(const <String, dynamic>{
        'words': 'not-a-list',
      });
      expect(empty.words, isEmpty);
      expect(empty.maxWords, ExclusionWords.defaultMaxWords);
    });

    test('getExclusionWords sends GET with the bearer token', () async {
      late http.Request captured;
      final client = DatingApiClient(
        baseUrl: 'http://api.test/api/v1',
        httpClient: MockClient((request) async {
          captured = request;
          return _json({'words': ['курю'], 'maxWords': 20}, 200);
        }),
      );
      client.token = 'tkn';

      final result = await client.getExclusionWords();

      expect(captured.method, 'GET');
      expect(
        captured.url.toString(),
        'http://api.test/api/v1/me/exclusion-words',
      );
      expect(captured.headers['Authorization'], 'Bearer tkn');
      expect(result.words, ['курю']);
    });

    test('updateExclusionWords sends PUT with the full list', () async {
      late http.Request captured;
      final client = DatingApiClient(
        baseUrl: 'http://api.test/api/v1',
        httpClient: MockClient((request) async {
          captured = request;
          return _json({
            'words': ['курю', 'алкоголь'],
            'maxWords': 20,
          }, 200);
        }),
      );
      client.token = 'tkn';

      final result = await client.updateExclusionWords(['Курю', 'алкоголь']);

      expect(captured.method, 'PUT');
      expect(
        captured.url.toString(),
        'http://api.test/api/v1/me/exclusion-words',
      );
      expect(captured.headers['Authorization'], 'Bearer tkn');
      expect(jsonDecode(captured.body), {
        'words': ['Курю', 'алкоголь'],
      });
      expect(result.words, ['курю', 'алкоголь']);
      expect(result.maxWords, 20);
    });

    test('updateExclusionWords with an empty list still sends the body',
        () async {
      late http.Request captured;
      final client = DatingApiClient(
        baseUrl: 'http://api.test/api/v1',
        httpClient: MockClient((request) async {
          captured = request;
          return _json({'words': <String>[], 'maxWords': 20}, 200);
        }),
      );
      client.token = 'tkn';

      final result = await client.updateExclusionWords(const <String>[]);

      expect(captured.method, 'PUT');
      expect(jsonDecode(captured.body), {'words': <String>[]});
      expect(result.words, isEmpty);
    });

    test('validation error exposes fieldErrors.words', () async {
      final client = DatingApiClient(
        baseUrl: 'http://api.test/api/v1',
        httpClient: MockClient(
          (_) async => _json(
            {
              'code': 'VALIDATION_ERROR',
              'message': 'Проверьте данные',
              'traceId': 't-2',
              'fieldErrors': {'words': 'Не больше 20 слов'},
            },
            400,
          ),
        ),
      );
      client.token = 'tkn';

      try {
        await client.updateExclusionWords(['курю']);
        fail('expected ApiException');
      } on ApiException catch (e) {
        expect(e.code, 'VALIDATION_ERROR');
        expect(describeError(e, field: 'words'), 'Не больше 20 слов');
      }
    });

    test('client-side rules mirror the contract', () {
      String? check(String raw, [List<String> existing = const <String>[]]) =>
          validateExclusionWord(
            normalizeExclusionWord(raw),
            existing: existing,
            maxWords: 20,
          );

      expect(normalizeExclusionWord('  КуРю '), 'курю');
      expect(check('курю'), isNull);
      expect(check('Smoke42'), isNull);
      expect(check('я'), isNotNull); // too short
      expect(check('а' * 31), isNotNull); // too long
      expect(check('а' * 30), isNull);
      expect(check('два слова'), isNotNull);
      expect(check('курю!'), isNotNull);
      expect(check('КУРЮ', ['курю']), isNotNull); // case-insensitive dupe
      expect(
        check('новое', List<String>.generate(20, (i) => 'слово$i')),
        isNotNull,
      ); // limit
    });
  });
}
