import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/account.dart';
import '../models/achievement.dart';
import '../models/auth.dart';
import '../models/discovery_filters.dart';
import '../models/discovery_page.dart';
import '../models/excluded_goals.dart';
import '../models/exclusion_words.dart';
import '../models/goal.dart';
import '../models/my_profile.dart';
import '../models/tag.dart';
import '../models/testing_state.dart';
import '../models/wallet.dart';
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

  // ------------------------------------------------------------- Account

  /// Requires auth. The caller's account (incl. the `testAccount` flag).
  Future<Account> getMe() async {
    final json = await _send('GET', '/me');
    return Account.fromJson(_expectMap(json));
  }

  /// Requires auth. Deletes the account (profile, photos, sessions); the
  /// token becomes invalid immediately.
  Future<void> deleteAccount() async {
    await _send('DELETE', '/me');
  }

  // ---------------------------------------------------------- My profile

  /// Requires auth. The caller's own profile.
  Future<MyProfile> getMyProfile() async {
    final json = await _send('GET', '/me/profile');
    return MyProfile.fromJson(_expectMap(json));
  }

  /// Requires auth. Partial update: only the given fields are sent.
  ///
  /// [clearHeight] removes the height and cannot be combined with
  /// [heightCm] (the server answers 400 `fieldErrors.heightCm`).
  /// `goalCodes: []` clears the goals (at most 3, 400 UNKNOWN_GOAL for
  /// unknown codes).
  Future<MyProfile> updateMyProfile({
    int? heightCm,
    bool clearHeight = false,
    List<String>? goalCodes,
  }) async {
    if (clearHeight && heightCm != null) {
      throw ArgumentError('clearHeight cannot be combined with heightCm');
    }
    final json = await _send(
      'PUT',
      '/me/profile',
      body: {
        if (heightCm != null) 'heightCm': heightCm,
        if (clearHeight) 'clearHeight': true,
        if (goalCodes != null) 'goalCodes': List<String>.of(goalCodes),
      },
    );
    return MyProfile.fromJson(_expectMap(json));
  }

  // ---------------------------------------------------------------- Tags

  Future<List<Tag>> getTags() async {
    final json = await _send('GET', '/tags', authenticated: false);
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(Tag.fromJson).toList();
  }

  // --------------------------------------------------------------- Goals

  /// Public. Relationship goals sorted by `sortOrder`.
  Future<List<Goal>> getGoals() async {
    final json = await _send('GET', '/goals', authenticated: false);
    if (json is! List) throw _badResponse();
    return Goal.parseList(json);
  }

  // ----------------------------------------------------------- Discovery

  /// Requires auth. [page] is zero-based, [limit] is 1..50.
  ///
  /// [minHeight], [maxHeight] and [goalCodes] are premium
  /// ([PremiumFeatureCodes.advancedFilters]); without premium the server
  /// answers 403 PREMIUM_REQUIRED.
  Future<DiscoveryPage> getDiscoveryProfiles({
    int page = 0,
    int limit = 20,
    String? city,
    int? minAge,
    int? maxAge,
    List<String> tagCodes = const <String>[],
    String tagLogic = 'OR',
    int? minHeight,
    int? maxHeight,
    List<String> goalCodes = const <String>[],
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
        if (minHeight != null) 'minHeight': '$minHeight',
        if (maxHeight != null) 'maxHeight': '$maxHeight',
        if (goalCodes.isNotEmpty) 'goalCodes': goalCodes.join(','),
      },
    );
    return DiscoveryPage.fromJson(_expectMap(json));
  }

  /// [getDiscoveryProfiles] with the values of [filters].
  Future<DiscoveryPage> getDiscoveryPage(
    DiscoveryFilters filters, {
    int page = 0,
    int limit = 20,
  }) =>
      getDiscoveryProfiles(
        page: page,
        limit: limit,
        city: filters.city,
        minAge: filters.minAge,
        maxAge: filters.maxAge,
        minHeight: filters.minHeight,
        maxHeight: filters.maxHeight,
        goalCodes: filters.goalCodes,
      );

  // ------------------------------------------------------- Excluded goals

  /// Requires auth. Saved goals whose profiles are hidden from the feed;
  /// `active: false` = saved but not applied (no premium).
  Future<ExcludedGoals> getExcludedGoals() async {
    final json = await _send('GET', '/me/excluded-goals');
    return ExcludedGoals.fromJson(_expectMap(json));
  }

  /// Requires auth and premium (403 PREMIUM_REQUIRED otherwise). Replaces
  /// the whole list; an empty list disables the filter. 400 UNKNOWN_GOAL
  /// for unknown codes.
  Future<ExcludedGoals> updateExcludedGoals(List<String> goalCodes) async {
    final json = await _send(
      'PUT',
      '/me/excluded-goals',
      body: {'goalCodes': List<String>.of(goalCodes)},
    );
    return ExcludedGoals.fromJson(_expectMap(json));
  }

  // ----------------------------------------------------- Exclusion words

  /// Requires auth. The caller's private list of feed exclusion words.
  Future<ExclusionWords> getExclusionWords() async {
    final json = await _send('GET', '/me/exclusion-words');
    return ExclusionWords.fromJson(_expectMap(json));
  }

  /// Requires auth and premium (403 PREMIUM_REQUIRED otherwise). Replaces
  /// the whole list (an empty list disables the filter). Returns the list as
  /// normalized by the server; on validation failure throws [ApiException]
  /// with `fieldErrors['words']`.
  Future<ExclusionWords> updateExclusionWords(List<String> words) async {
    final json = await _send(
      'PUT',
      '/me/exclusion-words',
      body: {'words': List<String>.of(words)},
    );
    return ExclusionWords.fromJson(_expectMap(json));
  }

  // ---------------------------------------------------- Wallet, cosmetics

  /// Requires auth. Premium status, consumables and cosmetics. Opening the
  /// wallet also grants due premium allowances on the server.
  Future<Wallet> getWallet() async {
    final json = await _send('GET', '/me/wallet');
    return Wallet.fromJson(_expectMap(json));
  }

  /// Requires auth. Puts the owned cosmetic [itemCode] into [slot] and
  /// returns the updated wallet.
  Future<Wallet> equipCosmetic(String slot, String itemCode) async {
    final json = await _send(
      'PUT',
      '/me/cosmetics/equipped/${_segment(slot)}',
      body: {'itemCode': itemCode},
    );
    return Wallet.fromJson(_expectMap(json));
  }

  /// Requires auth. Empties [slot] and returns the updated wallet.
  Future<Wallet> unequipCosmetic(String slot) async {
    final json =
        await _send('DELETE', '/me/cosmetics/equipped/${_segment(slot)}');
    return Wallet.fromJson(_expectMap(json));
  }

  // -------------------------------------------------------- Achievements

  Future<List<Achievement>> getAchievements() async {
    final json = await _send('GET', '/me/achievements');
    if (json is! List) throw _badResponse();
    return json
        .whereType<Map<String, dynamic>>()
        .map(Achievement.fromJson)
        .toList();
  }

  // ------------------------------------------------------------- Testing
  //
  // Test accounts only (403 TEST_ACCOUNT_REQUIRED otherwise); the endpoints
  // exist only when the server runs with app.testing.enabled=true. Every
  // call returns the full [TestingState].

  Future<TestingState> getTestingState() =>
      _testing('GET', '/testing/state');

  /// Marks the achievement completed and grants its rewards (once).
  Future<TestingState> completeTestAchievement(String code) =>
      _testing('POST', '/testing/achievements/${_segment(code)}/complete');

  /// Resets the completion; already granted rewards are kept.
  Future<TestingState> resetTestAchievement(String code) =>
      _testing('DELETE', '/testing/achievements/${_segment(code)}');

  /// Adds [quantity] (>= 1) units of a consumable or grants a cosmetic.
  Future<TestingState> grantTestItem(String itemCode, int quantity) => _testing(
        'POST',
        '/testing/wallet/items/${_segment(itemCode)}',
        body: {'quantity': quantity},
      );

  /// Sets the bought (PERMANENT) balance to exactly [quantity] (>= 0).
  Future<TestingState> setTestItemBalance(String itemCode, int quantity) =>
      _testing(
        'PUT',
        '/testing/wallet/items/${_segment(itemCode)}',
        body: {'quantity': quantity},
      );

  /// Adds [days] (1..3650) of premium.
  Future<TestingState> addTestPremiumDays(int days) =>
      _testing('POST', '/testing/premium', body: {'days': days});

  /// Ends premium now.
  Future<TestingState> removeTestPremium() =>
      _testing('DELETE', '/testing/premium');

  /// Moves this account's economy clock forward by [days] (1..3650);
  /// 7 = "next premium week".
  Future<TestingState> advanceTestClock(int days) =>
      _testing('POST', '/testing/clock/advance', body: {'days': days});

  /// Returns the economy clock to real time.
  Future<TestingState> resetTestClock() => _testing('DELETE', '/testing/clock');

  Future<TestingState> _testing(
    String method,
    String path, {
    Object? body,
  }) async {
    final json = await _send(method, path, body: body);
    return TestingState.fromJson(_expectMap(json));
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

  /// Encodes a value (item code, slot) used as a single path segment.
  static String _segment(String value) => Uri.encodeComponent(value);

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
