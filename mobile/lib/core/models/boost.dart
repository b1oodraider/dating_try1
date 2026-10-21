import 'json_utils.dart';

/// GET /me/boost (contract `BoostStatus`).
class BoostStatus {
  const BoostStatus({
    required this.active,
    required this.available,
    required this.durationMinutes,
    required this.now,
    this.endsAt,
    this.impressions = 0,
  });

  final bool active;
  final DateTime? endsAt;
  final int impressions;

  /// BOOST balance.
  final int available;
  final int durationMinutes;

  /// "Now" on the owner's clock (shifted for test accounts).
  final DateTime now;

  /// Time left of the active boost, measured on the server's clock.
  Duration leftAt(DateTime receivedAt) {
    final end = endsAt;
    if (!active || end == null) return Duration.zero;
    final left = end.difference(now) - DateTime.now().difference(receivedAt);
    return left.isNegative ? Duration.zero : left;
  }

  factory BoostStatus.fromJson(Map<String, dynamic> json) => BoostStatus(
        active: asBool(json['active']),
        endsAt: asDateTimeOrNull(json['endsAt']),
        impressions: asInt(json['impressions']),
        available: asInt(json['available']),
        durationMinutes: asInt(json['durationMinutes'], 30),
        now: asDateTimeOrNull(json['now']) ?? DateTime.now(),
      );
}
