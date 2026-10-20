import 'json_utils.dart';

/// A city from `GET /cities`.
class CitySuggestion {
  const CitySuggestion({
    required this.id,
    required this.name,
    required this.region,
    this.matchedName,
  });

  final int id;
  final String name;
  final String region;

  /// The alias that matched («Питер»), when it is not the official name.
  final String? matchedName;

  factory CitySuggestion.fromJson(Map<String, dynamic> json) => CitySuggestion(
        id: asInt(json['id']),
        name: asString(json['name']),
        region: asString(json['region']),
        matchedName: asStringOrNull(json['matchedName']),
      );
}
