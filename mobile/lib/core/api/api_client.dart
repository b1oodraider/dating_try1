import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/auth.dart';
import '../models/discovery_page.dart';
import '../models/tag.dart';
import 'api_config.dart';
import 'api_exception.dart';

typedef UnauthorizedHandler = Future<void> Function();

/// Thin HTTP client for the Dating API v1 (see docs/api-contract.md).
///
/// Every non-2xx response is turned into an [ApiException] carrying the
/// server's `code` / `message` / `fieldErrors`. A 401 on a request made with
/// the current token drops that token and calls [onUnauthorized].
class DatingApiClient {
  DatingApiClient({
    String? baseUrl,
    http.Client? httpClient,
    this.timeout = const Duration(seconds: 15),
  })  : baseUri = Uri.parse(
          ApiConfig.normalizeBaseUrl(baseUrl ?? ApiConfig.defaultBaseUrl),
        ),
        _http = httpClient ?? http.Client();

  /// e.g. http://10.0.2.2:8080/api/v1
  final Uri baseUri;
  final Duration timeout;
  final http.Client _http;

  String? _token;

  /// Called after a 401 for the current token (token already cleared here).
  UnauthorizedHandler? onUnauthorized;

  String? get token => _token;
  set token(String? value) {
    _token = (value == null || value.isEmpty) ? null : value;
  }

  bool get hasToken => _token != null;

  /// Scheme + host + port of the API, e.g. http://10.0.2.2:8080.
  Uri get origin => Uri.parse(baseUri.origin);

  /// Photo URLs come as relative signed paths ("/api/v1/media/photos/..?exp&sig");
  /// this prefixes the API origin. Absolute URLs are returned unchanged.
  String resolveMediaUrl(String url) => origin.resolve(url).toString();

  void close() => _http.close();

  // ---------------------------------------------------------------- Auth

  Future<OtpChallenge> requestOtp(String phone) async {
    final json = await _send(
      'POST',
      '/auth/otp/request',
      body: {'phone': phone},
      authenticated: false,
    );
    final challenge = OtpChallenge.fromJson(_expectMap(json));
    if (challenge.challengeId.isEmpty) throw _badResponse();
    return challenge;
  }

  /// Body is exactly `{challengeId, code}` — the phone is NOT sent.
  Future<AuthToken> verifyOtp({
    required String challengeId,
    required String code,
  }) async {
    final json = await _send(
      'POST',
      '/auth/otp/verify',
      body: {'challengeId': challengeId, 'code': code},
      authenticated: false,
    );
    final auth = AuthToken.fromJson(_expectMap(json));
    if (auth.token.isEmpty) throw _badResponse();
    return auth;
  }

  /// Revokes [token] on the server. Takes the token explicitly so the local
  /// session can be dropped first (logout never blocks on the network).
  Future<void> logout(String token) async {
    await _send('POST', '/auth/logout', bearerToken: token);
  }

  // ---------------------------------------------------------------- Tags

  Future<List<Tag>> getTags() async {
    final json = await _send('GET', '/tags', authenticated: false);
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(Tag.fromJson).toList();
  }

  // ----------------------------------------------------------- Discovery

  /// Requires auth. [page] is zero-based, [limit] is 1..50.
  Future<DiscoveryPage> getDiscoveryProfiles({
    int page = 0,
    int limit = 20,
    String? city,
    int? minAge,
    int? maxAge,
    List<String> tagCodes = const <String>[],
    String tagLogic = 'OR',
  }) async {
    final trimmedCity = city?.trim();
    final json = await _send(
      'GET',
      '/discovery/profiles',
      query: {
        'page': '$page',
        'limit': '$limit',
        if (trimmedCity != null && trimmedCity.isNotEmpty) 'city': trimmedCity,
        if (minAge != null) 'minAge': '$minAge',
        if (maxAge != null) 'maxAge': '$maxAge',
        if (tagCodes.isNotEmpty) ...{
          'tagCodes': tagCodes.join(','),
          'tagLogic': tagLogic,
        },
      },
    );
    return DiscoveryPage.fromJson(_expectMap(json));
  }

  // ------------------------------------------------------------ Internals

  Future<Object?> _send(
    String method,
    String path, {
    Map<String, String>? query,
    Object? body,
    bool authenticated = true,
    String? bearerToken,
  }) async {
    final request = http.Request(method, _uri(path, query));
    request.headers['Accept'] = 'application/json';

    final token = bearerToken ?? (authenticated ? _token : null);
    if (token != null) request.headers['Authorization'] = 'Bearer $token';

    if (body != null) {
      request.headers['Content-Type'] = 'application/json; charset=utf-8';
      request.body = jsonEncode(body);
    }

    final http.Response response;
    try {
      final streamed = await _http.send(request).timeout(timeout);
      response = await http.Response.fromStream(streamed).timeout(timeout);
    } on TimeoutException {
      throw const ApiException(
        statusCode: 0,
        code: 'TIMEOUT',
        message: 'Сервер не отвечает. Попробуйте ещё раз',
      );
    } on http.ClientException {
      throw const ApiException(
        statusCode: 0,
        code: 'NETWORK_ERROR',
        message: 'Нет соединения с сервером. Проверьте интернет и адрес API',
      );
    }

    final status = response.statusCode;
    final decoded = _decodeBody(response);
    if (status >= 200 && status < 300) return decoded;

    // Only react to a 401 for the token we currently hold: a stale request
    // (e.g. finished after re-login, or an explicit logout) must not wipe a
    // fresh session.
    if (status == 401 && token != null && token == _token) {
      _token = null;
      await onUnauthorized?.call();
    }

    throw ApiException.fromResponse(
      status,
      decoded,
      traceIdHeader: response.headers['x-trace-id'],
    );
  }

  Uri _uri(String path, Map<String, String>? query) {
    final uri = baseUri.replace(path: '${baseUri.path}$path');
    if (query == null || query.isEmpty) return uri;
    return uri.replace(queryParameters: query);
  }

  /// Always decode as UTF-8: `response.body` falls back to latin1 when the
  /// server omits the charset, which garbles Russian text.
  Object? _decodeBody(http.Response response) {
    if (response.bodyBytes.isEmpty) return null;
    try {
      return jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      return null;
    }
  }

  Map<String, dynamic> _expectMap(Object? json) {
    if (json is Map<String, dynamic>) return json;
    throw _badResponse();
  }

  static ApiException _badResponse() => const ApiException(
        statusCode: 200,
        code: 'BAD_RESPONSE',
        message: 'Некорректный ответ сервера',
      );
}
