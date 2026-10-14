import 'achievement.dart';
import 'json_utils.dart';
import 'wallet.dart';

/// Returned by every `/testing/*` endpoint (test accounts only).
class TestingState {
  const TestingState({
    required this.accountId,
    required this.clockOffsetDays,
    required this.economyNow,
    required this.wallet,
    required this.achievements,
  });

  final String accountId;

  /// How far this account's economy clock is ahead of real time (may be
  /// fractional).
  final double clockOffsetDays;

  /// Current economy time of this account.
  final DateTime? economyNow;
  final Wallet wallet;
  final List<Achievement> achievements;

  factory TestingState.fromJson(Map<String, dynamic> json) {
    return TestingState(
      accountId: asString(json['accountId']),
      clockOffsetDays: asDoubleOrNull(json['clockOffsetDays']) ?? 0,
      economyNow: asDateTimeOrNull(json['economyNow']),
      wallet: Wallet.fromJson(asJsonMap(json['wallet'])),
      achievements:
          asList<Achievement>(json['achievements'], Achievement.fromJson),
    );
  }
}
