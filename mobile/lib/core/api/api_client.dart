import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/account.dart';
import '../models/achievement.dart';
import '../models/auth.dart';
import '../models/city.dart';
import '../models/discovery_filters.dart';
import '../models/discovery_page.dart';
import '../models/excluded_goals.dart';
import '../models/exclusion_words.dart';
import '../models/gacha.dart';
import '../models/goal.dart';
import '../models/boost.dart';
import '../models/catalog.dart';
import '../models/date_plan.dart';
import '../models/facts.dart';
import '../models/interest.dart';
import '../models/json_utils.dart';
import '../models/my_profile.dart';
import '../models/photo.dart';
import '../models/public_profile.dart';
import '../models/social.dart';
import '../models/support.dart';
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
    String? displayName,
    int? age,
    String? city,
    String? aboutText,
    List<String>? tagCodes,
    String? dailyStatusText,
    String? gender,
    String? seeking,
    DateTime? birthDate,
    int? ageFrom,
    int? ageTo,
    bool clearAgeTo = false,
    int? cityId,
    int? searchRadiusKm,
    bool clearSearchRadius = false,
    bool? invisible,
    bool? showPremiumBadge,
    List<String>? interestCodes,
    Map<String, String>? interestComments,
    List<int>? artistIds,
    bool? showZodiac,
    int? heightCm,
    bool clearHeight = false,
    List<String>? goalCodes,
    Map<String, Object>? facts,
  }) async {
    if (clearHeight && heightCm != null) {
      throw ArgumentError('clearHeight cannot be combined with heightCm');
    }
    final json = await _send(
      'PUT',
      '/me/profile',
      body: {
        if (displayName != null) 'displayName': displayName,
        if (age != null) 'age': age,
        if (city != null) 'city': city,
        if (aboutText != null) 'aboutText': aboutText,
        if (tagCodes != null) 'tagCodes': List<String>.of(tagCodes),
        if (dailyStatusText != null) 'dailyStatusText': dailyStatusText,
        if (gender != null) 'gender': gender,
        if (seeking != null) 'seeking': seeking,
        if (birthDate != null) 'birthDate': _isoDate(birthDate),
        if (ageFrom != null) 'ageFrom': ageFrom,
        if (ageTo != null) 'ageTo': ageTo,
        if (clearAgeTo) 'clearAgeTo': true,
        if (cityId != null) 'cityId': cityId,
        if (searchRadiusKm != null) 'searchRadiusKm': searchRadiusKm,
        if (clearSearchRadius) 'clearSearchRadius': true,
        if (invisible != null) 'invisible': invisible,
        if (showPremiumBadge != null) 'showPremiumBadge': showPremiumBadge,
        if (interestCodes != null) 'interestCodes': List<String>.of(interestCodes),
        if (interestComments != null) 'interestComments': Map<String, String>.of(interestComments),
        if (artistIds != null) 'artistIds': List<int>.of(artistIds),
        if (showZodiac != null) 'showZodiac': showZodiac,
        if (heightCm != null) 'heightCm': heightCm,
        if (clearHeight) 'clearHeight': true,
        if (goalCodes != null) 'goalCodes': List<String>.of(goalCodes),
        ...?facts,
      },
    );
    return MyProfile.fromJson(_expectMap(json));
  }

  /// Requires auth. 400 PROFILE_INCOMPLETE with fieldErrors keyed by the
  /// missing fields.
  Future<MyProfile> publishProfile() async =>
      MyProfile.fromJson(_expectMap(await _send('POST', '/me/profile/publish')));

  Future<MyProfile> hideProfile() async =>
      MyProfile.fromJson(_expectMap(await _send('POST', '/me/profile/hide')));

  // -------------------------------------------------------------- Photos

  /// JPEG/PNG/WebP up to 5 MB, at most 6 photos.
  Future<Photo> uploadPhoto(List<int> bytes, String filename) async =>
      Photo.fromJson(_expectMap(
          await _sendFile('/me/profile/photos', bytes, filename)));

  Future<void> makePhotoPrimary(String photoId) async {
    await _send('PATCH', '/me/profile/photos/${_segment(photoId)}');
  }

  Future<void> deletePhoto(String photoId) async {
    await _send('DELETE', '/me/profile/photos/${_segment(photoId)}');
  }

  /// Public. City suggestions for any spelling («Питер», «Мск», typos).
  Future<List<CitySuggestion>> searchCities(String query) async {
    final json = await _send('GET', '/cities',
        query: {'query': query, 'limit': '10'}, authenticated: false);
    if (json is! List) throw _badResponse();
    return json
        .whereType<Map<String, dynamic>>()
        .map(CitySuggestion.fromJson)
        .toList();
  }

  /// Shares an approximate location (the server rounds it to ~1 km).
  Future<MyProfile> updateLocation(double latitude, double longitude) async =>
      MyProfile.fromJson(_expectMap(await _send('PUT', '/me/location',
          body: {'latitude': latitude, 'longitude': longitude})));

  Future<MyProfile> clearLocation() async =>
      MyProfile.fromJson(_expectMap(await _send('DELETE', '/me/location')));

  // ---------------------------------------------------------------- Tags

  Future<List<Tag>> getTags() async {
    final json = await _send('GET', '/tags', authenticated: false);
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(Tag.fromJson).toList();
  }

  // ---------------------------------------------------- Interests, music

  /// The interest dictionary grouped by category (public).
  Future<List<InterestGroup>> getInterests() async {
    final json = await _send('GET', '/interests', authenticated: false);
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(InterestGroup.fromJson).toList();
  }

  /// Answers for profile facts (labels to show).
  Future<ProfileOptions> getProfileOptions() async =>
      ProfileOptions.fromJson(await _send('GET', '/profile-options', authenticated: false));

  Future<Map<String, FactFilter>> getFactFilters() async =>
      FactFilter.mapFromJson(await _send('GET', '/me/fact-filters'));

  Future<Map<String, FactFilter>> putFactFilters(Map<String, FactFilter> filters) async =>
      FactFilter.mapFromJson(await _send('PUT', '/me/fact-filters',
          body: {for (final e in filters.entries) e.key: e.value.toJson()}));

  /// Artist search; a miss may be looked up on MusicBrainz by the server.
  Future<List<Artist>> searchArtists(String query) async {
    final json = await _send('GET', '/artists', query: {'q': query});
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(Artist.fromJson).toList();
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

  // ----------------------------------------------------------- Reactions

  Future<ReactionResult> like(String profileId) => _reaction(profileId, 'like');

  /// [idempotencyKey]: new per tap, reused on retry (spends one SUPERLIKE).
  Future<ReactionResult> superlike(String profileId, String idempotencyKey) =>
      _reaction(profileId, 'superlike', body: {'idempotencyKey': idempotencyKey});

  Future<ReactionResult> pass(String profileId) => _reaction(profileId, 'pass');

  Future<ReactionResult> _reaction(String profileId, String kind,
      {Object? body}) async {
    final json = await _send(
      'POST',
      '/discovery/profiles/${_segment(profileId)}/$kind',
      body: body,
    );
    return ReactionResult.fromJson(_expectMap(json));
  }

  /// Premium UNDO_SWIPE: takes back my last like or pass; returns the profile
  /// to put back on top of the deck.
  Future<PublicProfile> undoLastReaction() async =>
      PublicProfile.fromJson(_expectMap(await _send('POST', '/me/reactions/undo')));

  /// Brings passed profiles back. Without premium only with [feedEmpty] and
  /// once a day (409 FEED_NOT_EMPTY / 429 PASS_REFRESH_TOO_SOON).
  Future<int> refreshPasses({required bool feedEmpty}) async => asInt(_expectMap(
          await _send('POST', '/me/passes/refresh', query: {'feedEmpty': '$feedEmpty'}))['restored']);

  /// Time each card was on screen, for the like-spam rule (1..100 views).
  Future<void> sendProfileViews(List<Map<String, Object>> views) async {
    if (views.isEmpty) return;
    await _send('POST', '/discovery/views', body: {'views': views});
  }

  /// 403 PREMIUM_REQUIRED (feature WHO_LIKED_ME) when it is a premium feature.
  Future<ReceivedLikesPage> getReceivedLikes({int page = 0, int limit = 50}) async {
    final json = await _send('GET', '/me/likes/received',
        query: {'page': '$page', 'limit': '$limit'});
    return ReceivedLikesPage.fromJson(_expectMap(json));
  }

  Future<int> getReceivedLikesCount() async =>
      asInt(_expectMap(await _send('GET', '/me/likes/received/count'))['count']);

  // ---------------------------------------------------------------- Chat

  Future<List<Match>> getMatches() async {
    final json = await _send('GET', '/matches');
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(Match.fromJson).toList();
  }

  Future<void> unmatch(String matchId) async {
    await _send('DELETE', '/matches/${_segment(matchId)}');
  }

  /// Newest first; [before] = id of the oldest message already loaded.
  Future<MessagePage> getMessages(String matchId,
      {String? before, int limit = 30}) async {
    final json = await _send(
      'GET',
      '/matches/${_segment(matchId)}/messages',
      query: {'limit': '$limit', if (before != null) 'before': before},
    );
    return MessagePage.fromJson(_expectMap(json));
  }

  /// [clientMessageId] is generated once per message and reused on retry.
  Future<ChatMessage> sendMessage(
    String matchId, {
    required String clientMessageId,
    String? text,
    String? stickerPackCode,
    String? sticker,
    String? replyToMessageId,
  }) async {
    final json = await _send(
      'POST',
      '/matches/${_segment(matchId)}/messages',
      body: {
        'clientMessageId': clientMessageId,
        'type': text != null ? 'TEXT' : 'STICKER',
        if (text != null) 'text': text,
        if (stickerPackCode != null) 'stickerPackCode': stickerPackCode,
        if (sticker != null) 'sticker': sticker,
        if (replyToMessageId != null) 'replyToMessageId': replyToMessageId,
      },
    );
    return ChatMessage.fromJson(_expectMap(json));
  }

  // ------------------------------------------------------------- Date plan

  /// The plan in play in this chat, or null.
  Future<DatePlan?> getDatePlan(String matchId) async {
    final plan = _expectMap(await _send('GET', '/matches/${_segment(matchId)}/date-plan'))['plan'];
    return plan is Map<String, dynamic> ? DatePlan.fromJson(plan) : null;
  }

  Future<DatePlan> getDatePlanById(String planId) async =>
      DatePlan.fromJson(_expectMap(await _send('GET', '/date-plans/${_segment(planId)}')));

  /// 403 PREMIUM_REQUIRED (DATE_PLAN), 409 PLAN_ALREADY_OPEN, 400 VALIDATION_ERROR (fieldErrors items[i].…).
  Future<DatePlan> proposeDatePlan(String matchId, List<DatePlanItem> items) async => DatePlan.fromJson(
      _expectMap(await _send('POST', '/matches/${_segment(matchId)}/date-plan',
          body: {'items': [for (final item in items) item.toJson()]})));

  /// A counter proposal; 409 PLAN_CHANGED when [version] is stale.
  Future<DatePlan> editDatePlan(String planId, int version, List<DatePlanItem> items) async => DatePlan.fromJson(
      _expectMap(await _send('PUT', '/date-plans/${_segment(planId)}',
          body: {'version': version, 'items': [for (final item in items) item.toJson()]})));

  Future<DatePlan> answerDatePlan(String planId, String action) async =>
      DatePlan.fromJson(_expectMap(await _send('POST', '/date-plans/${_segment(planId)}/${_segment(action)}')));

  /// Places around a point, sponsored first (each with its ad label).
  /// Address or place name near the map centre; [] when the search is unavailable.
  Future<List<FoundPlace>> searchPlaces(String query, double latitude, double longitude) async {
    final json = await _send('GET', '/places/search',
        query: {'q': query, 'latitude': '$latitude', 'longitude': '$longitude'});
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(FoundPlace.fromJson).toList();
  }

  Future<List<Venue>> getVenues(double latitude, double longitude) async {
    final json = await _send('GET', '/venues',
        query: {'latitude': '$latitude', 'longitude': '$longitude'});
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(Venue.fromJson).toList();
  }

  /// Testing API: three test-only places around the point, one sponsored.
  Future<void> createTestVenues(double latitude, double longitude) async {
    await _send('POST', '/testing/venues', body: {'latitude': latitude, 'longitude': longitude});
  }

  /// «Печатает…» for the other person; call at most every few seconds while typing.
  Future<void> sendTyping(String matchId) async {
    await _send('POST', '/matches/${_segment(matchId)}/typing');
  }

  Future<void> markRead(String matchId, String lastReadMessageId) async {
    await _send('POST', '/matches/${_segment(matchId)}/read',
        body: {'lastReadMessageId': lastReadMessageId});
  }

  // ------------------------------------------------------------- Safety

  /// «Not interested»: only I stop seeing this person.
  Future<void> hide(String profileId) async {
    await _send('POST', '/accounts/${_segment(profileId)}/hide');
  }

  Future<void> block(String profileId) async {
    await _send('POST', '/accounts/${_segment(profileId)}/block');
  }

  /// Returns the note to show when the report was closed at once (complaints
  /// about silence), otherwise null.
  Future<String?> report({
    required String profileId,
    required String reason,
    String? messageId,
    String? comment,
  }) async {
    final json = _expectMap(await _send('POST', '/reports', body: {
      'profileId': profileId,
      'reason': reason,
      if (messageId != null) 'messageId': messageId,
      if (comment != null && comment.trim().isNotEmpty) 'comment': comment.trim(),
    }));
    return json['status'] == 'DISMISSED' ? asStringOrNull(json['note']) : null;
  }

  // -------------------------------------------------------------- Support

  Future<List<SupportTicket>> getSupportTickets() async {
    final json = await _send('GET', '/support/tickets');
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(SupportTicket.fromJson).toList();
  }

  Future<SupportTicket> getSupportTicket(String id) async =>
      SupportTicket.fromJson(_expectMap(await _send('GET', '/support/tickets/${_segment(id)}')));

  /// FAQ as (question, answer), in display order. Public.
  Future<List<(String, String)>> getFaq() async {
    final json = await _send('GET', '/faq', authenticated: false);
    if (json is! List) throw _badResponse();
    return [
      for (final item in json.whereType<Map<String, dynamic>>())
        (asString(item['question']), asString(item['answer'])),
    ];
  }

  /// 429 RATE_LIMITED after 5 new tickets a day.
  Future<SupportTicket> createSupportTicket(String category, String text) async => SupportTicket.fromJson(
      _expectMap(await _send('POST', '/support/tickets', body: {'category': category, 'text': text})));

  Future<SupportTicket> writeToSupport(String ticketId, String text) async => SupportTicket.fromJson(
      _expectMap(await _send('POST', '/support/tickets/${_segment(ticketId)}/messages', body: {'text': text})));

  Future<BoostStatus> getBoost() async => BoostStatus.fromJson(_expectMap(await _send('GET', '/me/boost')));

  /// 409 BOOST_ALREADY_ACTIVE / INSUFFICIENT_BALANCE; the same [idempotencyKey] never spends twice.
  Future<BoostStatus> activateBoost(String idempotencyKey) async => BoostStatus.fromJson(
      _expectMap(await _send('POST', '/me/boost', body: {'idempotencyKey': idempotencyKey})));

  Future<List<Restriction>> getRestrictions() async {
    final json = await _send('GET', '/me/restrictions');
    if (json is! List) throw _badResponse();
    return json
        .whereType<Map<String, dynamic>>()
        .map(Restriction.fromJson)
        .toList();
  }

  Future<VerificationState> getVerification() async =>
      VerificationState.fromJson(
          _expectMap(await _send('GET', '/me/verification')));

  /// The open request (same gesture) or a new one with a random gesture.
  /// The user must have agreed to comparing their photos (400 CONSENT_REQUIRED).
  Future<VerificationRequest> requestVerification() async =>
      VerificationRequest.fromJson(_expectMap(await _send(
          'POST', '/me/verification',
          body: {'faceComparisonConsent': true})));

  Future<VerificationRequest> submitSelfie(
          String requestId, List<int> bytes, String filename) async =>
      VerificationRequest.fromJson(_expectMap(await _sendFile(
          '/me/verification/${_segment(requestId)}/selfie', bytes, filename)));

  // --------------------------------------------------------------- Gacha

  /// Banners open now (without the full pool).
  Future<List<GachaBanner>> getGachaBanners() async {
    final json = await _send('GET', '/gacha/banners');
    if (json is! List) throw _badResponse();
    return json
        .whereType<Map<String, dynamic>>()
        .map(GachaBanner.fromJson)
        .toList();
  }

  /// One banner with its full pool and the base chances.
  Future<GachaBanner> getGachaBanner(String code) async => GachaBanner.fromJson(
      _expectMap(await _send('GET', '/gacha/banners/${_segment(code)}')));

  /// [count] is 1 or the banner's multiPullSize; [idempotencyKey] is new per
  /// tap and reused on retry (the same results come back, nothing is spent).
  Future<PullResponse> pullGacha(
          String code, int count, String idempotencyKey) async =>
      PullResponse.fromJson(_expectMap(await _send(
        'POST',
        '/gacha/banners/${_segment(code)}/pull',
        body: {'count': count, 'idempotencyKey': idempotencyKey},
      )));

  Future<GachaHistoryPage> getGachaHistory({int limit = 30, int? before}) async =>
      GachaHistoryPage.fromJson(_expectMap(await _send(
        'GET',
        '/gacha/history',
        query: {'limit': '$limit', if (before != null) 'before': '$before'},
      )));

  Future<ShardsInfo> getGachaShards() async =>
      ShardsInfo.fromJson(_expectMap(await _send('GET', '/gacha/shards')));

  Future<void> exchangeShards(int tickets, String idempotencyKey) async {
    await _send('POST', '/gacha/shards/exchange',
        body: {'tickets': tickets, 'idempotencyKey': idempotencyKey});
  }

  // ------------------------------------------------------ Testing: social
  //
  // Seed profiles act on my behalf: they like me, match with me, write to me.

  /// Generates [count] (1..50) published seed profiles that match me; with [likeMe] all of them like me.
  /// Returns how many were created.
  Future<int> createSeedCrowd(int count, {bool likeMe = false}) async => asInt(_expectMap(await _send(
      'POST', '/testing/profiles/crowd',
      body: {'count': count, if (likeMe) 'likeMe': true}))['created']);

  Future<List<SeedProfileRef>> getSeedProfiles() async {
    final json = await _send('GET', '/testing/profiles');
    if (json is! List) throw _badResponse();
    return json
        .whereType<Map<String, dynamic>>()
        .map(SeedProfileRef.fromJson)
        .toList();
  }

  Future<void> seedLikesMe(String seedAccountId, {bool superlike = false}) async {
    await _send('POST', '/testing/social/seed/${_segment(seedAccountId)}/like-me',
        body: {'superlike': superlike});
  }

  /// Returns the match id.
  Future<String> seedMatch(String seedAccountId) async => asString(_expectMap(
          await _send('POST', '/testing/social/seed/${_segment(seedAccountId)}/match'))[
      'matchId']);

  /// The seed of my match writes [text] to me.
  Future<void> seedWrites(String matchId, String text) async {
    await _send('POST', '/testing/social/matches/${_segment(matchId)}/messages',
        body: {'text': text});
  }

  /// Forgets my passes (those profiles come back to the feed); returns how many.
  Future<int> clearTestPasses() async =>
      asInt(_expectMap(await _send('DELETE', '/testing/social/passes'))['deleted']);

  /// Forgets all my likes, super likes and passes (matches stay).
  Future<int> clearTestReactions() async => asInt(
      _expectMap(await _send('DELETE', '/testing/social/reactions'))['deleted']);

  // ----------------------------------------------------------- Realtime

  /// `GET /events` as a raw SSE byte stream, authorized by the header
  /// (no ticket needed outside the browser). The caller parses and closes it.
  Future<http.StreamedResponse> openEventStream(http.Client client) async {
    final request = http.Request('GET', _uri('/events', null));
    request.headers['Accept'] = 'text/event-stream';
    final token = _token;
    if (token != null) request.headers['Authorization'] = 'Bearer $token';
    final response = await client.send(request).timeout(timeout);
    if (response.statusCode == 401 && token != null && token == _token) {
      _token = null;
      await onUnauthorized?.call();
    }
    return response;
  }

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

  /// Purchase STUB (no payment yet): PREMIUM_MONTH, SUPERLIKE_PACK or BOOST_PACK is granted at once.
  Future<Wallet> buyStub(String product) async =>
      Wallet.fromJson(_expectMap(await _send('POST', '/me/purchases/stub', body: {'product': product})));

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

  /// Sets the bought (PERMANENT) balance to exactly [quantity] (>= 0); with [total] the whole balance,
  /// premium allowance units included.
  Future<TestingState> setTestItemBalance(String itemCode, int quantity, {bool total = false}) =>
      _testing(
        'PUT',
        '/testing/wallet/items/${_segment(itemCode)}',
        body: {'quantity': quantity, if (total) 'total': true},
      );

  /// Active catalog items, sorted by kind, category, code.
  Future<List<CatalogItem>> getCatalog() async {
    final json = await _send('GET', '/catalog');
    if (json is! List) throw _badResponse();
    return json.whereType<Map<String, dynamic>>().map(CatalogItem.fromJson).toList();
  }

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

  /// POSTs one file as multipart field `file` (photos, selfies).
  Future<Object?> _sendFile(String path, List<int> bytes, String filename) async {
    final request = http.MultipartRequest('POST', _uri(path, null))
      ..headers['Accept'] = 'application/json'
      ..files.add(http.MultipartFile.fromBytes('file', bytes, filename: filename));
    final token = _token;
    if (token != null) request.headers['Authorization'] = 'Bearer $token';

    final http.Response response;
    try {
      final streamed =
          await _http.send(request).timeout(const Duration(seconds: 60));
      response = await http.Response.fromStream(streamed);
    } on TimeoutException {
      throw const ApiException(
        statusCode: 0,
        code: 'TIMEOUT',
        message: 'Загрузка не завершилась. Попробуйте ещё раз',
      );
    } on http.ClientException {
      throw const ApiException(
        statusCode: 0,
        code: 'NETWORK_ERROR',
        message: 'Нет соединения с сервером. Проверьте интернет и адрес API',
      );
    }
    final decoded = _decodeBody(response);
    if (response.statusCode >= 200 && response.statusCode < 300) return decoded;
    if (response.statusCode == 401 && token != null && token == _token) {
      _token = null;
      await onUnauthorized?.call();
    }
    throw ApiException.fromResponse(response.statusCode, decoded,
        traceIdHeader: response.headers['x-trace-id']);
  }

  static String _isoDate(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

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
