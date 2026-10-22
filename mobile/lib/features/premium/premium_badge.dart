import 'package:flutter/material.dart';

import '../../core/models/discovery_filters.dart';

/// Small "Премиум" chip with a crown, shown next to premium users' names
/// and in the premium status block.
class PremiumBadge extends StatelessWidget {
  const PremiumBadge({super.key});

  static const String label = 'Премиум';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Semantics(
      label: label,
      excludeSemantics: true,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colors.tertiaryContainer,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.workspace_premium,
                size: 16,
                color: colors.onTertiaryContainer,
              ),
              const SizedBox(width: 4),
              Text(
                label,
                style: theme.textTheme.labelMedium?.copyWith(
                  color: colors.onTertiaryContainer,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Hint for a premium-only control the user cannot use right now.
class PremiumLockedHint extends StatelessWidget {
  const PremiumLockedHint({
    super.key,
    this.text = 'Доступно с премиумом',
    this.onTap,
  });

  final String text;

  /// Opens the premium page (the hint then reads as a link).
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = onTap == null ? theme.colorScheme.onSurfaceVariant : theme.colorScheme.primary;
    final row = Row(
      children: [
        Icon(Icons.lock_outline, size: 16, color: color),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            text,
            style: theme.textTheme.bodySmall?.copyWith(color: color),
          ),
        ),
        if (onTap != null) Icon(Icons.chevron_right_rounded, size: 18, color: color),
      ],
    );
    return onTap == null
        ? row
        : InkWell(onTap: onTap, child: Padding(padding: const EdgeInsets.symmetric(vertical: 6), child: row));
  }
}

/// Human-readable name of a premium feature code; unknown codes as is.
String premiumFeatureLabel(String code) => switch (code) {
      PremiumFeatureCodes.advancedFilters => 'расширенный поиск',
      PremiumFeatureCodes.exclusionWords => 'слова-исключения',
      _ => code,
    };
