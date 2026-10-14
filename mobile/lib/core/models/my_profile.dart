import 'goal.dart';
import 'json_utils.dart';

/// The caller's own profile (`GET/PUT /me/profile`).
///
/// Only the fields this client edits or shows are parsed; the rest of the
/// contract type `MyProfile` is ignored.
class MyProfile {
  const MyProfile({
    required this.id,
    required this.displayName,
    this.heightCm,
    this.goals = const <Goal>[],
    this.premium = false,
  });

  static const int heightMin = 120;
  static const int heightMax = 230;
  static const int maxGoals = 3;

  final String id;
  final String? displayName;

  /// Centimetres; null when not specified.
  final int? heightCm;

  /// Sorted by [Goal.sortOrder].
  final List<Goal> goals;
  final bool premium;

  factory MyProfile.fromJson(Map<String, dynamic> json) {
    return MyProfile(
      id: asString(json['id']),
      displayName: asStringOrNull(json['displayName']),
      heightCm: asIntOrNull(json['heightCm']),
      goals: Goal.parseList(json['goals']),
      premium: asBool(json['premium']),
    );
  }
}
