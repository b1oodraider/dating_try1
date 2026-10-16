import 'json_utils.dart';

/// Equip slots known to this client. Other slots may appear in the API and
/// must simply be ignored.
class CosmeticSlots {
  CosmeticSlots._();

  static const String photoFrame = 'PHOTO_FRAME';
  static const String profileStatus = 'PROFILE_STATUS';
  static const String chatWallpaper = 'CHAT_WALLPAPER';
}

/// A public cosmetic shown on a profile (contract type `Decoration`).
///
/// Named `ProfileDecoration` to avoid clashing with Flutter's `Decoration`.
/// Rendering is driven by [attributes]; unknown keys are ignored.
class ProfileDecoration {
  const ProfileDecoration({
    required this.code,
    required this.title,
    required this.rarity,
    required this.attributes,
  });

  final String code;
  final String title;

  /// COMMON | RARE | EPIC | LEGENDARY, or null.
  final String? rarity;
  final Map<String, Object?> attributes;

  factory ProfileDecoration.fromJson(Map<String, dynamic> json) {
    return ProfileDecoration(
      code: asString(json['code']),
      title: asString(json['title']),
      rarity: asStringOrNull(json['rarity']),
      attributes: asAttributes(json['attributes']),
    );
  }

  /// Contract `Decorations`: `{slot: Decoration}`. Non-object entries are
  /// skipped; unknown slots are kept but no widget renders them.
  static Map<String, ProfileDecoration> parseAll(Object? value) {
    if (value is! Map) return const <String, ProfileDecoration>{};
    final result = <String, ProfileDecoration>{};
    value.forEach((slot, item) {
      if (slot is String && item is Map<String, dynamic>) {
        result[slot] = ProfileDecoration.fromJson(item);
      }
    });
    return Map<String, ProfileDecoration>.unmodifiable(result);
  }
}

/// PHOTO_FRAME attributes: `{borderColor: '#RRGGBB', borderWidth, glow?}`.
class PhotoFrameStyle {
  const PhotoFrameStyle({
    required this.color,
    required this.width,
    required this.glow,
  });

  static const double defaultWidth = 3;
  static const double maxWidth = 12;

  /// Opaque ARGB value.
  final int color;
  final double width;
  final bool glow;

  /// Null when the frame cannot be drawn (no valid color).
  static PhotoFrameStyle? fromAttributes(Map<String, Object?> attributes) {
    final color = parseHexColor(attributes['borderColor']);
    if (color == null) return null;
    final width = asDoubleOrNull(attributes['borderWidth']) ?? defaultWidth;
    return PhotoFrameStyle(
      color: color,
      width: width.clamp(1, maxWidth).toDouble(),
      glow: asBool(attributes['glow']),
    );
  }
}

/// PROFILE_STATUS attributes: `{emoji, label, color: '#RRGGBB'}`.
class ProfileStatusStyle {
  const ProfileStatusStyle({
    required this.emoji,
    required this.label,
    required this.color,
  });

  final String emoji;
  final String label;

  /// Opaque ARGB value, null when missing or malformed (use a theme color).
  final int? color;

  /// Null when there is nothing to show (no emoji and no label).
  static ProfileStatusStyle? fromAttributes(Map<String, Object?> attributes) {
    final emoji = asString(attributes['emoji']).trim();
    final label = asString(attributes['label']).trim();
    if (emoji.isEmpty && label.isEmpty) return null;
    return ProfileStatusStyle(
      emoji: emoji,
      label: label,
      color: parseHexColor(attributes['color']),
    );
  }

  String get text => [emoji, label].where((part) => part.isNotEmpty).join(' ');
}
