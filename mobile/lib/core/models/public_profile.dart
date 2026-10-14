import 'decoration.dart';
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
    );
  }
}
