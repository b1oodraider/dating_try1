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
    this.scalePosition,
  });

  final String code;

  /// One of: pace, format, calls, boundaries, social.
  final String category;
  final String categoryLabel;
  final String label;
  final String description;
  final int sortOrder;

  /// Place on the category's scale (smaller = closer to its min end); null = the category is not a scale.
  final int? scalePosition;

  factory Tag.fromJson(Map<String, dynamic> json) {
    return Tag(
      code: asString(json['code']),
      category: asString(json['category']),
      categoryLabel: asString(json['categoryLabel']),
      label: asString(json['label']),
      description: asString(json['description']),
      sortOrder: asInt(json['sortOrder']),
      scalePosition: asIntOrNull(json['scalePosition']),
    );
  }
}
