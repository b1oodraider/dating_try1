import 'goal.dart';
import 'json_utils.dart';
import 'photo.dart';
import 'tag.dart';

/// A completeness tip (`MyProfile.tips`): what to fill in and how much it adds.
class ProfileTip {
  const ProfileTip({
    required this.code,
    required this.text,
    required this.weight,
    required this.done,
    required this.requiredToPublish,
  });

  final String code;
  final String text;
  final int weight;
  final bool done;
  final bool requiredToPublish;

  factory ProfileTip.fromJson(Map<String, dynamic> json) => ProfileTip(
        code: asString(json['code']),
        text: asString(json['text']),
        weight: asInt(json['weight']),
        done: asBool(json['done']),
        requiredToPublish: asBool(json['requiredToPublish']),
      );
}

/// The caller's own profile (`GET/PUT /me/profile`).
class MyProfile {
  const MyProfile({
    required this.id,
    required this.displayName,
    this.age,
    this.gender,
    this.seeking,
    this.birthDate,
    this.ageFrom = 18,
    this.ageTo,
    this.cityId,
    this.searchRadiusKm,
    this.locationShared = false,
    this.invisible = false,
    this.showPremiumBadge = true,
    this.city,
    this.aboutText,
    this.dailyStatusText,
    this.publicationStatus = 'DRAFT',
    this.tags = const <Tag>[],
    this.photos = const <Photo>[],
    this.tips = const <ProfileTip>[],
    this.completenessPercent = 0,
    this.missingForPublish = const <String>[],
    this.verified = false,
    this.heightCm,
    this.goals = const <Goal>[],
    this.premium = false,
  });

  static const int heightMin = 120;
  static const int heightMax = 230;
  static const int maxGoals = 3;
  static const int maxTags = 10;
  static const int maxPhotos = 6;

  final String id;
  final String? displayName;

  /// Full years, computed by the server from [birthDate].
  final int? age;

  /// MALE or FEMALE; locked after the first publish.
  final String? gender;

  /// Who I look for: MALE or FEMALE.
  final String? seeking;

  /// Date only; locked after the first publish.
  final DateTime? birthDate;

  /// Ages I accept (both ways); [ageTo] null = no upper limit.
  final int ageFrom;
  final int? ageTo;

  /// Gender can no longer be changed in the app, the age neither (the exact date can be refined).
  bool get identityLocked => publicationStatus != 'DRAFT';
  final String? city;

  /// Dictionary city; [city] is its official name.
  final int? cityId;

  /// Search radius in km; null = no radius.
  final int? searchRadiusKm;

  /// An approximate device location is shared (distances in the feed).
  final bool locationShared;

  /// Invisible mode (premium): only people I liked see me.
  final bool invisible;

  /// Others see my premium badge.
  final bool showPremiumBadge;
  final String? aboutText;
  final String? dailyStatusText;

  /// DRAFT, PUBLISHED or HIDDEN.
  final String publicationStatus;
  final List<Tag> tags;

  /// Sorted by [Photo.sortOrder].
  final List<Photo> photos;

  /// Not done first, most valuable first.
  final List<ProfileTip> tips;
  final int completenessPercent;

  /// Subset of displayName, age, city, photos; empty = can publish.
  final List<String> missingForPublish;
  final bool verified;

  /// Centimetres; null when not specified.
  final int? heightCm;

  /// Sorted by [Goal.sortOrder].
  final List<Goal> goals;
  final bool premium;

  bool get isPublished => publicationStatus == 'PUBLISHED';

  factory MyProfile.fromJson(Map<String, dynamic> json) {
    final photos = asList<Photo>(json['photos'], Photo.fromJson)
      ..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
    return MyProfile(
      id: asString(json['id']),
      displayName: asStringOrNull(json['displayName']),
      age: asIntOrNull(json['age']),
      gender: asStringOrNull(json['gender']),
      seeking: asStringOrNull(json['seeking']),
      birthDate: asDateTimeOrNull(json['birthDate']),
      ageFrom: asInt(json['ageFrom'], 18),
      ageTo: asIntOrNull(json['ageTo']),
      city: asStringOrNull(json['city']),
      cityId: asIntOrNull(json['cityId']),
      searchRadiusKm: asIntOrNull(json['searchRadiusKm']),
      locationShared: asBool(json['locationShared']),
      invisible: asBool(json['invisible']),
      showPremiumBadge: json['showPremiumBadge'] != false,
      aboutText: asStringOrNull(json['aboutText']),
      dailyStatusText: asStringOrNull(json['dailyStatusText']),
      publicationStatus: asString(json['publicationStatus'], 'DRAFT'),
      tags: asList<Tag>(json['tags'], Tag.fromJson),
      photos: photos,
      tips: asList<ProfileTip>(json['tips'], ProfileTip.fromJson),
      completenessPercent: asInt(json['completenessPercent']),
      missingForPublish: asStringList(json['missingForPublish']),
      verified: asBool(json['verified']),
      heightCm: asIntOrNull(json['heightCm']),
      goals: Goal.parseList(json['goals']),
      premium: asBool(json['premium']),
    );
  }
}
