import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/models/decoration.dart';
import '../../core/models/json_utils.dart';
import '../../core/models/wallet.dart';
import '../../core/theme/app_theme.dart';

// Shared rendering of cosmetics and items: the feed card, the collection and
// the test panel all draw them the same way. Everything is driven by the
// catalog attributes; unknown categories fall back to a neutral look.

/// Color of a rarity tier; null for no / unknown rarity.
Color? rarityColor(String? rarity) {
  switch (rarity) {
    // One ladder inside the brand palette (docs/design-audit.md): grey, ink, rose, gold.
    case 'COMMON':
      return const Color(0xFF8A8286);
    case 'RARE':
      return const Color(0xFF4A4245);
    case 'EPIC':
      return Brand.rose;
    case 'LEGENDARY':
      return Brand.gold;
  }
  return null;
}

String? rarityLabel(String? rarity) {
  switch (rarity) {
    case 'COMMON':
      return 'Обычная';
    case 'RARE':
      return 'Редкая';
    case 'EPIC':
      return 'Эпическая';
    case 'LEGENDARY':
      return 'Легендарная';
  }
  return rarity;
}

/// Section title for an item category (plural).
String categoryLabel(String category) {
  switch (category) {
    case 'SUPERLIKE':
      return 'Суперлайки';
    case 'GACHA_TICKET':
      return 'Билеты удачи';
    case 'BOOST':
      return 'Бусты';
    case 'PHOTO_FRAME':
      return 'Рамки для фото';
    case 'PROFILE_STATUS':
      return 'Статусы профиля';
    case 'CHAT_WALLPAPER':
      return 'Обои чата';
    case 'STICKER_PACK':
      return 'Стикерпаки';
  }
  return category.isEmpty ? 'Другое' : category;
}

/// Display order of cosmetic categories; unknown ones go last.
const List<String> cosmeticCategoryOrder = <String>[
  'PHOTO_FRAME',
  'PROFILE_STATUS',
  'CHAT_WALLPAPER',
  'STICKER_PACK',
];

IconData consumableIcon(Map<String, Object?> attributes) {
  switch (attributes['icon']) {
    case 'star':
      return Icons.star;
    case 'ticket':
      return Icons.confirmation_number;
    case 'rocket':
      return Icons.rocket_launch;
  }
  return Icons.inventory_2;
}

/// Small colored label with the rarity name.
class RarityBadge extends StatelessWidget {
  const RarityBadge({super.key, required this.rarity});

  final String? rarity;

  @override
  Widget build(BuildContext context) {
    final label = rarityLabel(rarity);
    if (label == null) return const SizedBox.shrink();
    final color =
        rarityColor(rarity) ?? Theme.of(context).colorScheme.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: color,
              fontWeight: FontWeight.w600,
            ),
      ),
    );
  }
}

/// PHOTO_FRAME: a colored border (optionally glowing) around [child].
class FramedPhoto extends StatelessWidget {
  const FramedPhoto({
    super.key,
    required this.frame,
    required this.child,
    this.radius = 12,
  });

  final PhotoFrameStyle frame;
  final Widget child;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final color = Color(frame.color);
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: color, width: frame.width),
        boxShadow: frame.glow
            ? [
                BoxShadow(
                  color: color.withValues(alpha: 0.7),
                  blurRadius: 12,
                  spreadRadius: 1,
                ),
              ]
            : null,
      ),
      child: Padding(
        padding: EdgeInsets.all(frame.width),
        child: ClipRRect(
          borderRadius:
              BorderRadius.circular(math.max(0, radius - frame.width)),
          child: child,
        ),
      ),
    );
  }
}

/// PROFILE_STATUS: emoji + label in the status color.
class ProfileStatusChip extends StatelessWidget {
  const ProfileStatusChip({super.key, required this.status});

  final ProfileStatusStyle status;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final statusColor = status.color;
    final color =
        statusColor == null ? theme.colorScheme.primary : Color(statusColor);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Text(
        status.text,
        style: theme.textTheme.labelLarge?.copyWith(
          color: color,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

/// 48×48 preview of an owned item, drawn from its attributes.
class ItemPreview extends StatelessWidget {
  const ItemPreview({super.key, required this.item});

  static const double size = 48;

  final WalletItem item;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final attributes = item.attributes;

    if (item.isConsumable) {
      return _box(
        color: scheme.primaryContainer,
        child: Icon(consumableIcon(attributes), color: scheme.primary),
      );
    }

    switch (item.category) {
      case CosmeticSlots.photoFrame:
        final frame = PhotoFrameStyle.fromAttributes(attributes);
        if (frame == null) break;
        return SizedBox.square(
          dimension: size,
          child: FramedPhoto(
            frame: frame,
            radius: 8,
            child: ColoredBox(
              color: scheme.secondaryContainer,
              child: const Center(child: Icon(Icons.person, size: 20)),
            ),
          ),
        );
      case CosmeticSlots.profileStatus:
        final status = ProfileStatusStyle.fromAttributes(attributes);
        if (status == null) break;
        final statusColor = status.color;
        return _box(
          color: (statusColor == null ? scheme.primary : Color(statusColor))
              .withValues(alpha: 0.15),
          child: Text(
            status.emoji.isNotEmpty ? status.emoji : '★',
            style: const TextStyle(fontSize: 24),
          ),
        );
      case CosmeticSlots.chatWallpaper:
        final background = parseHexColor(attributes['background']);
        final accent = parseHexColor(attributes['accent']);
        if (background == null) break;
        return _box(
          color: Color(background),
          child: Icon(
            Icons.chat_bubble,
            size: 20,
            color: accent == null ? Colors.white : Color(accent),
          ),
        );
      case 'STICKER_PACK':
        final emoji = asString(attributes['emoji']).trim();
        if (emoji.isEmpty) break;
        return _box(
          color: scheme.tertiaryContainer,
          child: Text(emoji, style: const TextStyle(fontSize: 24)),
        );
    }
    return _box(
      color: scheme.surfaceContainerHighest,
      child: Icon(Icons.auto_awesome, color: scheme.onSurfaceVariant),
    );
  }

  Widget _box({required Color color, required Widget child}) {
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(10),
      ),
      child: child,
    );
  }
}
