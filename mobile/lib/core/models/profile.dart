import 'communication_tag.dart';

class ProfilePhoto {
  final String id;
  final String url;
  final bool isPrimary;
  final int sortOrder;

  const ProfilePhoto({
    required this.id,
    required this.url,
    required this.isPrimary,
    required this.sortOrder,
  });

  factory ProfilePhoto.fromJson(Map<String, dynamic> json) {
    return ProfilePhoto(
      id: json['id'] as String,
      url: json['url'] as String,
      isPrimary: json['isPrimary'] as bool? ?? false,
      sortOrder: json['sortOrder'] as int? ?? 0,
    );
  }
}

class Profile {
  final String id;
  final String accountId;
  final String displayName;
  final int age;
  final String city;
  final String? aboutText;
  final String publicationStatus;
  final String? dailyStatusText;
  final List<CommunicationTag> tags;
  final List<ProfilePhoto> photos;
  final int completenessPercent;

  const Profile({
    required this.id,
    required this.accountId,
    required this.displayName,
    required this.age,
    required this.city,
    this.aboutText,
    required this.publicationStatus,
    this.dailyStatusText,
    required this.tags,
    required this.photos,
    required this.completenessPercent,
  });

  factory Profile.fromJson(Map<String, dynamic> json) {
    final tagsList = (json['tags'] as List<dynamic>?)
            ?.map((e) => CommunicationTag.fromJson(e as Map<String, dynamic>))
            .toList() ??
        [];
    final photosList = (json['photos'] as List<dynamic>?)
            ?.map((e) => ProfilePhoto.fromJson(e as Map<String, dynamic>))
            .toList() ??
        [];

    return Profile(
      id: json['id'] as String,
      accountId: json['accountId'] as String,
      displayName: json['displayName'] as String,
      age: json['age'] as int,
      city: json['city'] as String,
      aboutText: json['aboutText'] as String?,
      publicationStatus: json['publicationStatus'] as String? ?? 'DRAFT',
      dailyStatusText: json['dailyStatusText'] as String?,
      tags: tagsList,
      photos: photosList,
      completenessPercent: json['completenessPercent'] as int? ?? 0,
    );
  }
}
