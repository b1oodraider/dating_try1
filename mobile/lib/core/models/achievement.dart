import 'json_utils.dart';

/// `GET /me/achievements` item (contract type `Achievement`).
class Achievement {
  const Achievement({
    required this.code,
    required this.title,
    required this.description,
    required this.completed,
    required this.completedAt,
    required this.rewards,
  });

  final String code;
  final String title;
  final String? description;
  final bool completed;
  final DateTime? completedAt;
  final List<AchievementReward> rewards;

  String get displayTitle => title.isNotEmpty ? title : code;

  factory Achievement.fromJson(Map<String, dynamic> json) {
    return Achievement(
      code: asString(json['code']),
      title: asString(json['title']),
      description: asStringOrNull(json['description']),
      completed: asBool(json['completed']),
      completedAt: asDateTimeOrNull(json['completedAt']),
      rewards: asList<AchievementReward>(
        json['rewards'],
        AchievementReward.fromJson,
      ),
    );
  }
}

/// `{type: 'ITEM'|'PREMIUM_DAYS', itemCode?, quantity?, days?}`.
class AchievementReward {
  const AchievementReward({
    required this.type,
    this.itemCode,
    this.quantity,
    this.days,
  });

  static const String typeItem = 'ITEM';
  static const String typePremiumDays = 'PREMIUM_DAYS';

  final String type;
  final String? itemCode;
  final int? quantity;
  final int? days;

  factory AchievementReward.fromJson(Map<String, dynamic> json) {
    return AchievementReward(
      type: asString(json['type']),
      itemCode: asStringOrNull(json['itemCode']),
      quantity: asIntOrNull(json['quantity']),
      days: asIntOrNull(json['days']),
    );
  }
}
