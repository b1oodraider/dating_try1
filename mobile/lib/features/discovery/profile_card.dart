import 'package:flutter/material.dart';

import '../../core/models/decoration.dart';
import '../../core/models/public_profile.dart';
import '../cosmetics/cosmetic_visuals.dart';
import '../premium/premium_badge.dart';

class ProfileCard extends StatelessWidget {
  const ProfileCard({super.key, required this.profile, this.photoUrl});

  final PublicProfile profile;

  /// Absolute URL of the primary photo, already resolved against the API origin.
  final String? photoUrl;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final title = [
      if (profile.displayName.isNotEmpty) profile.displayName else 'Без имени',
      if (profile.age != null) '${profile.age}',
    ].join(', ');
    final city = profile.city;
    final height = profile.heightCm;
    final details = [
      if (city != null && city.isNotEmpty) city,
      if (height != null && height > 0) '$height см',
    ].join(' · ');
    final goals = profile.goals.map((goal) => goal.displayLabel).join(', ');
    final status = profile.dailyStatusText;
    final about = profile.aboutText;

    // Only known public slots are rendered; anything else is ignored.
    final frameDecoration = profile.decorations[CosmeticSlots.photoFrame];
    final frame = frameDecoration == null
        ? null
        : PhotoFrameStyle.fromAttributes(frameDecoration.attributes);
    final statusDecoration = profile.decorations[CosmeticSlots.profileStatus];
    final profileStatus = statusDecoration == null
        ? null
        : ProfileStatusStyle.fromAttributes(statusDecoration.attributes);

    final photo = AspectRatio(
      aspectRatio: 4 / 3,
      child: _ProfilePhoto(url: photoUrl),
    );

    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (frame == null)
            photo
          else
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 14, 14, 0),
              child: FramedPhoto(frame: frame, child: photo),
            ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: 8,
                  runSpacing: 4,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(title, style: theme.textTheme.titleLarge),
                    if (profile.premium) const PremiumBadge(),
                  ],
                ),
                if (profileStatus != null) ...[
                  const SizedBox(height: 6),
                  ProfileStatusChip(status: profileStatus),
                  const SizedBox(height: 4),
                ],
                if (details.isNotEmpty)
                  Text(
                    details,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                if (goals.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(
                        Icons.flag_outlined,
                        size: 18,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          'Ищу: $goals',
                          style: theme.textTheme.bodyMedium,
                        ),
                      ),
                    ],
                  ),
                ],
                if (status != null && status.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(
                    '«$status»',
                    style: theme.textTheme.bodyMedium?.copyWith(
                      fontStyle: FontStyle.italic,
                    ),
                  ),
                ],
                if (about != null && about.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(about, maxLines: 3, overflow: TextOverflow.ellipsis),
                ],
                if (profile.tags.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 6,
                    runSpacing: 6,
                    children: [
                      for (final tag in profile.tags)
                        Chip(
                          label: Text(tag.label),
                          visualDensity: VisualDensity.compact,
                        ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ProfilePhoto extends StatelessWidget {
  const _ProfilePhoto({required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    final background = Theme.of(context).colorScheme.secondaryContainer;
    final placeholder = ColoredBox(
      color: background,
      child: const Center(child: Icon(Icons.person, size: 64)),
    );

    final imageUrl = url;
    if (imageUrl == null || imageUrl.isEmpty) return placeholder;

    // Signed URLs expire (~1h); on error show the placeholder —
    // pull-to-refresh fetches fresh URLs.
    return Image.network(
      imageUrl,
      fit: BoxFit.cover,
      loadingBuilder: (context, child, progress) {
        if (progress == null) return child;
        return ColoredBox(
          color: background,
          child: const Center(child: CircularProgressIndicator()),
        );
      },
      errorBuilder: (context, error, stackTrace) => placeholder,
    );
  }
}
