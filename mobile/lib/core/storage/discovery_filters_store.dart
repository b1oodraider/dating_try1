import 'dart:convert';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/discovery_filters.dart';

/// Persists the discovery query filters per account (key includes the
/// accountId), so they survive an app restart.
///
/// Premium-only values (height, goals) are stored as is; whether they are
/// sent is decided by the caller from the current premium state.
/// Corrupt or missing data loads as [DiscoveryFilters.none]; storage errors
/// are logged and never thrown.
class DiscoveryFiltersStore {
  DiscoveryFiltersStore({Future<SharedPreferences> Function()? preferences})
      : _preferences = preferences ?? SharedPreferences.getInstance;

  static const String keyPrefix = 'discovery_filters.v1.';

  final Future<SharedPreferences> Function() _preferences;

  static String keyFor(String accountId) => '$keyPrefix$accountId';

  Future<DiscoveryFilters> load(String accountId) async {
    if (accountId.isEmpty) return DiscoveryFilters.none;
    try {
      final prefs = await _preferences();
      final raw = prefs.getString(keyFor(accountId));
      if (raw == null) return DiscoveryFilters.none;
      // City and age moved to the profile («Кого и где ищу»); an old saved city would filter with no way to undo.
      final saved = decode(raw);
      return saved.onlyAdvanced().withCommunities(saved.communityIds);
    } catch (e) {
      debugPrint('Failed to load discovery filters: $e');
      return DiscoveryFilters.none;
    }
  }

  /// Saves [filters]; empty filters remove the key.
  Future<void> save(String accountId, DiscoveryFilters filters) async {
    if (accountId.isEmpty) return;
    try {
      final prefs = await _preferences();
      if (filters.isEmpty) {
        await prefs.remove(keyFor(accountId));
      } else {
        await prefs.setString(keyFor(accountId), encode(filters));
      }
    } catch (e) {
      debugPrint('Failed to save discovery filters: $e');
    }
  }

  /// Removes the saved filters of every account (logout, account deletion).
  Future<void> clearAll() async {
    try {
      final prefs = await _preferences();
      final keys =
          prefs.getKeys().where((key) => key.startsWith(keyPrefix)).toList();
      for (final key in keys) {
        await prefs.remove(key);
      }
    } catch (e) {
      debugPrint('Failed to clear discovery filters: $e');
    }
  }

  static String encode(DiscoveryFilters filters) => jsonEncode({
        if (filters.city != null) 'city': filters.city,
        if (filters.minAge != null) 'minAge': filters.minAge,
        if (filters.maxAge != null) 'maxAge': filters.maxAge,
        if (filters.minHeight != null) 'minHeight': filters.minHeight,
        if (filters.maxHeight != null) 'maxHeight': filters.maxHeight,
        if (filters.goalCodes.isNotEmpty) 'goalCodes': filters.goalCodes,
        if (filters.communityIds.isNotEmpty) 'communityIds': filters.communityIds,
      });

  /// Tolerant decoding: anything malformed is dropped; unparsable JSON
  /// gives [DiscoveryFilters.none].
  static DiscoveryFilters decode(String raw) {
    final Object? json;
    try {
      json = jsonDecode(raw);
    } on FormatException {
      return DiscoveryFilters.none;
    }
    if (json is! Map) return DiscoveryFilters.none;

    final city = json['city'];
    final (minAge, maxAge) = _range(
      json['minAge'],
      json['maxAge'],
      DiscoveryFilters.ageMin,
      DiscoveryFilters.ageMax,
    );
    final (minHeight, maxHeight) = _range(
      json['minHeight'],
      json['maxHeight'],
      DiscoveryFilters.heightMin,
      DiscoveryFilters.heightMax,
    );
    final rawGoals = json['goalCodes'];
    final goalCodes = rawGoals is List
        ? rawGoals
            .whereType<String>()
            .map((code) => code.trim())
            .where((code) => code.isNotEmpty)
            .toSet()
            .toList(growable: false)
        : const <String>[];

    final rawCommunities = json['communityIds'];
    final communityIds = rawCommunities is List
        ? rawCommunities.whereType<int>().where((id) => id > 0).toSet().toList(growable: false)
        : const <int>[];

    return DiscoveryFilters(
      communityIds: communityIds,
      city: city is String && city.trim().isNotEmpty ? city.trim() : null,
      minAge: minAge,
      maxAge: maxAge,
      minHeight: minHeight,
      maxHeight: maxHeight,
      goalCodes: goalCodes,
    );
  }

  /// Keeps whole numbers within [min]..[max]; an inverted pair is dropped.
  static (int?, int?) _range(Object? from, Object? to, int min, int max) {
    int? bound(Object? value) =>
        value is int && value >= min && value <= max ? value : null;
    final lower = bound(from);
    final upper = bound(to);
    if (lower != null && upper != null && lower > upper) return (null, null);
    return (lower, upper);
  }
}
