import 'decoration.dart';
import 'goal.dart';
import 'json_utils.dart';
import 'photo.dart';
import 'tag.dart';

/// Another user's published profile (contract type `PublicProfile`).
/// Intentionally has no accountId / statuses: the server does not expose them.
class PublicProfile {
  const PublicProfile({
    required this.id,
    required this.displayName,
    required this.age,
    required this.city,
    required this.aboutText,
    required this.dailyStatusText,
    required this.tags,
    required this.photos,
    this.decorations = const <String, ProfileDecoration>{},
    this.heightCm,
    this.goals = const <Goal>[],
    this.premium = false,
    this.verified = false,
    this.compatibility,
    this.distanceKm,
  });

  final String id;
  final String displayName;
  final int? age;
  final String? city;
  final String? aboutText;
  final String? dailyStatusText;
  final List<Tag> tags;

  /// Sorted by [Photo.sortOrder].
  final List<Photo> photos;

  /// Public cosmetics by slot (PHOTO_FRAME, PROFILE_STATUS, ...); may be
  /// empty. Unknown slots are kept here but not rendered.
  final Map<String, ProfileDecoration> decorations;

  /// Height in centimetres; null when not specified.
  final int? heightCm;

  /// Relationship goals, sorted by [Goal.sortOrder].
  final List<Goal> goals;

  /// Premium badge (shown to others in the feed).
  final bool premium;

  /// Selfie checked by a moderator.
  final bool verified;

  /// 0..100 match with me by cards; null when there is too little to compare.
  final int? compatibility;

  /// Whole km; only in the feed and only when both share their location.
  final int? distanceKm;

  Photo? get primaryPhoto {
    if (photos.isEmpty) return null;
    for (final photo in photos) {
      if (photo.isPrimary) return photo;
    }
    return photos.first;
  }

  factory PublicProfile.fromJson(Map<String, dynamic> json) {
    final photos = asList<Photo>(json['photos'], Photo.fromJson)
      ..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
    return PublicProfile(
      id: asString(json['id']),
      displayName: asString(json['displayName']),
      age: asIntOrNull(json['age']),
      city: asStringOrNull(json['city']),
      aboutText: asStringOrNull(json['aboutText']),
      dailyStatusText: asStringOrNull(json['dailyStatusText']),
      tags: asList<Tag>(json['tags'], Tag.fromJson),
      photos: photos,
      decorations: ProfileDecoration.parseAll(json['decorations']),
      heightCm: asIntOrNull(json['heightCm']),
      goals: Goal.parseList(json['goals']),
      premium: asBool(json['premium']),
      verified: asBool(json['verified']),
      compatibility: asIntOrNull(json['compatibility']),
      distanceKm: asIntOrNull(json['distanceKm']),
    );
  }
}
