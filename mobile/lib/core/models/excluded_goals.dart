import 'json_utils.dart';

/// Saved hard filter of `GET/PUT /me/excluded-goals`: profiles having any
/// of these goals are hidden from the caller's feed.
class ExcludedGoals {
  const ExcludedGoals({required this.goalCodes, this.active = true});

  final List<String> goalCodes;

  /// False when the list is saved but not applied (premium has ended);
  /// editing then returns 403 PREMIUM_REQUIRED.
  final bool active;

  factory ExcludedGoals.fromJson(Map<String, dynamic> json) {
    return ExcludedGoals(
      goalCodes: List<String>.unmodifiable(
        asStringList(json['goalCodes'])
            .map((code) => code.trim())
            .where((code) => code.isNotEmpty),
      ),
      active: asBool(json['active'], true),
    );
  }
}
