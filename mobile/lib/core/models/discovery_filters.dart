/// Query filters of `GET /discovery/profiles`.
///
/// City and age are free; height and goals are the premium feature
/// [PremiumFeatureCodes.advancedFilters] (the server answers 403
/// PREMIUM_REQUIRED otherwise).
class DiscoveryFilters {
  const DiscoveryFilters({
    this.city,
    this.minAge,
    this.maxAge,
    this.minHeight,
    this.maxHeight,
    this.goalCodes = const <String>[],
    this.communityIds = const <int>[],
  });

  static const DiscoveryFilters none = DiscoveryFilters();

  static const int ageMin = 18;
  static const int ageMax = 100;
  static const int heightMin = 120;
  static const int heightMax = 230;

  final String? city;
  final int? minAge;
  final int? maxAge;

  /// Centimetres; profiles without a height do not match.
  final int? minHeight;
  final int? maxHeight;

  /// Profile has at least one of these goals.
  final List<String> goalCodes;

  /// Interest groups (free): the person is subscribed to at least one of them.
  final List<int> communityIds;

  bool get hasBasic =>
      (city?.trim().isNotEmpty ?? false) || minAge != null || maxAge != null;

  bool get hasAdvanced =>
      minHeight != null || maxHeight != null || goalCodes.isNotEmpty;

  bool get isEmpty => !hasBasic && !hasAdvanced && communityIds.isEmpty;

  /// Same filters with height and goals dropped (for non-premium users).
  DiscoveryFilters withoutAdvanced() =>
      DiscoveryFilters(city: city, minAge: minAge, maxAge: maxAge, communityIds: communityIds);

  /// Same filters searching among these groups ([] = not by groups).
  DiscoveryFilters withCommunities(List<int> ids) => DiscoveryFilters(
        city: city,
        minAge: minAge,
        maxAge: maxAge,
        minHeight: minHeight,
        maxHeight: maxHeight,
        goalCodes: goalCodes,
        communityIds: ids,
      );

  /// Same filters with city and age dropped (height and goals kept).
  DiscoveryFilters onlyAdvanced() => DiscoveryFilters(
        minHeight: minHeight,
        maxHeight: maxHeight,
        goalCodes: goalCodes,
      );
}

/// Feature codes of `Wallet.premiumFeatures` used by the client.
abstract final class PremiumFeatureCodes {
  /// Discovery by height and goals + saved excluded goals.
  static const String advancedFilters = 'ADVANCED_FILTERS';

  /// Saved exclusion words.
  static const String exclusionWords = 'EXCLUSION_WORDS';
}
