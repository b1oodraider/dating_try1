import 'json_utils.dart';

/// Communication-style tag (contract type `Tag`).
class Tag {
  const Tag({
    required this.code,
    required this.category,
    required this.categoryLabel,
    required this.label,
    required this.description,
    required this.sortOrder,
  });

  final String code;

  /// One of: pace, format, calls, boundaries, social.
  final String category;
  final String categoryLabel;
  final String label;
  final String description;
  final int sortOrder;

  factory Tag.fromJson(Map<String, dynamic> json) {
    return Tag(
      code: asString(json['code']),
      category: asString(json['category']),
      categoryLabel: asString(json['categoryLabel']),
      label: asString(json['label']),
      description: asString(json['description']),
      sortOrder: asInt(json['sortOrder']),
    );
  }
}
