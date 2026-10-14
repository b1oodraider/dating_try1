class CommunicationTag {
  final String code;
  final String category;
  final String categoryLabel;
  final String label;
  final String description;
  final int sortOrder;

  const CommunicationTag({
    required this.code,
    required this.category,
    required this.categoryLabel,
    required this.label,
    required this.description,
    required this.sortOrder,
  });

  factory CommunicationTag.fromJson(Map<String, dynamic> json) {
    return CommunicationTag(
      code: json['code'] as String,
      category: json['category'] as String,
      categoryLabel: json['categoryLabel'] as String,
      label: json['label'] as String,
      description: json['description'] as String,
      sortOrder: json['sortOrder'] as int? ?? 0,
    );
  }

  Map<String, dynamic> toJson() => {
    'code': code,
    'category': category,
    'categoryLabel': categoryLabel,
    'label': label,
    'description': description,
    'sortOrder': sortOrder,
  };
}
