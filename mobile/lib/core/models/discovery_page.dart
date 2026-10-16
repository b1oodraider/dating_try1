import 'json_utils.dart';
import 'public_profile.dart';

/// One page of `GET /discovery/profiles`.
class DiscoveryPage {
  const DiscoveryPage({
    required this.items,
    required this.page,
    required this.limit,
    required this.totalElements,
    required this.totalPages,
  });

  final List<PublicProfile> items;

  /// Zero-based page index.
  final int page;
  final int limit;
  final int totalElements;
  final int totalPages;

  bool get hasMore => page + 1 < totalPages;

  factory DiscoveryPage.fromJson(Map<String, dynamic> json) {
    return DiscoveryPage(
      items: asList<PublicProfile>(json['items'], PublicProfile.fromJson),
      page: asInt(json['page']),
      limit: asInt(json['limit'], 20),
      totalElements: asInt(json['totalElements']),
      totalPages: asInt(json['totalPages']),
    );
  }
}
