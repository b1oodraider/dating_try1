import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/api/api_exception.dart';
import 'package:dating_mobile/core/models/discovery_page.dart';
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
}
