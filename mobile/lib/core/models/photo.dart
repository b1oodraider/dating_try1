import 'json_utils.dart';

/// Profile photo (contract type `Photo`).
class Photo {
  const Photo({
    required this.id,
    required this.url,
    required this.mimeType,
    required this.sizeBytes,
    required this.sortOrder,
    required this.isPrimary,
    required this.createdAt,
  });

  final String id;

  /// RELATIVE signed path, e.g. `/api/v1/media/photos/{id}?exp=..&sig=..`.
  /// Valid for about an hour and needs no Authorization header. Resolve it
  /// with `DatingApiClient.resolveMediaUrl` before passing to Image.network.
  final String url;
  final String? mimeType;
  final int sizeBytes;
  final int sortOrder;
  final bool isPrimary;
  final DateTime? createdAt;

  factory Photo.fromJson(Map<String, dynamic> json) {
    return Photo(
      id: asString(json['id']),
      url: asString(json['url']),
      mimeType: asStringOrNull(json['mimeType']),
      sizeBytes: asInt(json['sizeBytes']),
      sortOrder: asInt(json['sortOrder']),
      isPrimary: asBool(json['isPrimary']),
      createdAt: asDateTimeOrNull(json['createdAt']),
    );
  }
}
