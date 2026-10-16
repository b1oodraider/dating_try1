import 'json_utils.dart';

/// Relationship goal (contract type `Goal`, `GET /goals`).
class Goal {
  const Goal({
    required this.code,
    required this.label,
    required this.sortOrder,
  });

  /// SERIOUS_RELATIONSHIP, FAMILY, DATING, FRIENDSHIP, CHAT, NOT_SURE
  /// (open set).
  final String code;
  final String label;
  final int sortOrder;

  /// Label for display; falls back to the code.
  String get displayLabel => label.isNotEmpty ? label : code;

  factory Goal.fromJson(Map<String, dynamic> json) {
    return Goal(
      code: asString(json['code']),
      label: asString(json['label']),
      sortOrder: asInt(json['sortOrder']),
    );
  }

  /// Parses a JSON array of goals: drops entries without a code, sorts by
  /// [sortOrder].
  static List<Goal> parseList(Object? value) =>
      asList<Goal>(value, Goal.fromJson)
          .where((goal) => goal.code.isNotEmpty)
          .toList()
        ..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
}
