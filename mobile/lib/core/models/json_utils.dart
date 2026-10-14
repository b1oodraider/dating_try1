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
