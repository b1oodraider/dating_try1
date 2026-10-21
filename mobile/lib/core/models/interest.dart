import 'json_utils.dart';

/// An interest from the closed dictionary (contract `Interest`).
class Interest {
  const Interest({
    required this.code,
    required this.label,
    required this.category,
    required this.categoryLabel,
  });

  final String code;
  final String label;
  final String category;
  final String categoryLabel;

  factory Interest.fromJson(Map<String, dynamic> json) => Interest(
        code: asString(json['code']),
        label: asString(json['label']),
        category: asString(json['category']),
        categoryLabel: asString(json['categoryLabel']),
      );
}

/// GET /interests: one category with its interests, in display order.
class InterestGroup {
  const InterestGroup({required this.code, required this.label, required this.items});

  final String code;
  final String label;
  final List<Interest> items;

  factory InterestGroup.fromJson(Map<String, dynamic> json) => InterestGroup(
        code: asString(json['code']),
        label: asString(json['label']),
        items: asList<Interest>(json['items'], Interest.fromJson),
      );
}

/// A music artist (contract `Artist`).
class Artist {
  const Artist({required this.id, required this.name});

  final int id;
  final String name;

  factory Artist.fromJson(Map<String, dynamic> json) =>
      Artist(id: asInt(json['id']), name: asString(json['name']));

  @override
  bool operator ==(Object other) => other is Artist && other.id == id;

  @override
  int get hashCode => id.hashCode;
}
