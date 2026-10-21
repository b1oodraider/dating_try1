import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Centered «nothing here yet / something went wrong» block: tinted icon, title, hint, optional actions.
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    this.message,
    this.actions = const <Widget>[],
  });

  final IconData icon;
  final String title;
  final String? message;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(Gaps.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 88,
              height: 88,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: scheme.primary.withValues(alpha: 0.1),
              ),
              child: Icon(icon, size: 40, color: scheme.primary),
            ),
            const SizedBox(height: Gaps.lg),
            Text(title, textAlign: TextAlign.center, style: theme.textTheme.titleLarge),
            if (message != null) ...[
              const SizedBox(height: Gaps.sm),
              Text(
                message!,
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
              ),
            ],
            if (actions.isNotEmpty) ...[
              const SizedBox(height: Gaps.lg),
              for (final action in actions)
                Padding(
                  padding: const EdgeInsets.only(bottom: Gaps.sm),
                  child: action,
                ),
            ],
          ],
        ),
      ),
    );
  }
}
