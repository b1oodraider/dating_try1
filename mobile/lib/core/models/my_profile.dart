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
  final int? age;
  final String? city;
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
      city: asStringOrNull(json['city']),
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
