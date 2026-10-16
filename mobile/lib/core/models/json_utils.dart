// Defensive JSON helpers.
//
// The backend is evolving in parallel, so a missing or mistyped field must
// degrade gracefully (default value / null) instead of crashing the UI with a
// TypeError from a hard `as` cast.

Map<String, dynamic> asJsonMap(Object? value) =>
    value is Map<String, dynamic> ? value : const <String, dynamic>{};

String? asStringOrNull(Object? value) => value is String ? value : null;

String asString(Object? value, [String fallback = '']) =>
    value is String ? value : fallback;

/// JSON numbers may arrive as `int` or `double` (e.g. `27.0`).
int? asIntOrNull(Object? value) => value is num ? value.toInt() : null;

int asInt(Object? value, [int fallback = 0]) =>
    asIntOrNull(value) ?? fallback;

bool asBool(Object? value, [bool fallback = false]) =>
    value is bool ? value : fallback;

DateTime? asDateTimeOrNull(Object? value) =>
    value is String ? DateTime.tryParse(value) : null;

/// Parses a JSON array of objects, silently skipping non-object entries.
List<T> asList<T>(
  Object? value,
  T Function(Map<String, dynamic> json) parse,
) {
  if (value is! List) return <T>[];
  return value.whereType<Map<String, dynamic>>().map(parse).toList();
}

double? asDoubleOrNull(Object? value) => value is num ? value.toDouble() : null;

/// Parses a JSON array of strings, silently skipping non-string entries.
List<String> asStringList(Object? value) =>
    value is List ? value.whereType<String>().toList() : <String>[];

/// Free-form JSON object (e.g. catalog `attributes`): keeps string keys only;
/// anything that is not an object becomes an empty map.
Map<String, Object?> asAttributes(Object? value) {
  if (value is! Map) return const <String, Object?>{};
  final result = <String, Object?>{};
  value.forEach((key, item) {
    if (key is String) result[key] = item;
  });
  return Map<String, Object?>.unmodifiable(result);
}

/// `'#RRGGBB'` (`#` optional) → opaque ARGB int; null for anything malformed.
int? parseHexColor(Object? value) {
  if (value is! String) return null;
  var hex = value.trim();
  if (hex.startsWith('#')) hex = hex.substring(1);
  if (!RegExp(r'^[0-9a-fA-F]{6}$').hasMatch(hex)) return null;
  return 0xFF000000 | int.parse(hex, radix: 16);
}
