import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../core/models/decoration.dart';
import '../../core/models/facts.dart';
import '../../core/models/interest.dart';
import '../../core/models/public_profile.dart';
import '../cosmetics/cosmetic_visuals.dart';
import '../premium/premium_badge.dart';

/// The whole public profile: photo carousel on top, then the facts and
/// small titled sections (goals, status, about, interests, music, communication).
class ProfileCard extends StatelessWidget {
  const ProfileCard({super.key, required this.profile, this.photoUrl, this.options});

  final PublicProfile profile;

  /// Labels for the facts; without them the facts are not shown.
  final ProfileOptions? options;

  /// Resolves a relative signed photo path to an absolute URL; null shows placeholders.
  final String Function(String url)? photoUrl;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final title = [
      if (profile.displayName.isNotEmpty) profile.displayName else 'Без имени',
      if (profile.age != null) '${profile.age}',
    ].join(', ');
    final city = profile.city;
    final height = profile.heightCm;
    final zodiac = profile.zodiac;
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

    final resolve = photoUrl;
    final carousel = PhotoCarousel(
      urls: [
        for (final photo in profile.photos)
          resolve == null ? null : resolve(photo.url),
      ],
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (frame == null)
          carousel
        else
          FramedPhoto(frame: frame, radius: Radii.lg, child: carousel),
        const SizedBox(height: Gaps.md),
        Wrap(
          spacing: Gaps.sm,
          runSpacing: Gaps.xs,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(title, style: theme.textTheme.headlineSmall),
            if (profile.verified)
              const Tooltip(
                message: 'Селфи проверено модератором',
                child: Icon(Icons.verified, color: Brand.verified, size: 24),
              ),
            if (profile.premium) const PremiumBadge(),
          ],
        ),
        if (profileStatus != null) ...[
          const SizedBox(height: Gaps.sm),
          Align(
            alignment: Alignment.centerLeft,
            child: ProfileStatusChip(status: profileStatus),
          ),
        ],
        const SizedBox(height: Gaps.sm),
        Wrap(
          spacing: Gaps.md,
          runSpacing: Gaps.xs,
          children: [
            if (profile.compatibility != null)
              _Fact(Icons.auto_awesome, '${profile.compatibility}% совпадение',
                  color: scheme.primary),
            if (profile.distanceKm != null)
              _Fact(Icons.near_me_outlined, '~${profile.distanceKm} км')
            else if (city != null && city.isNotEmpty)
              _Fact(Icons.place_outlined, city),
            if (profile.distanceKm != null && city != null && city.isNotEmpty)
              _Fact(Icons.place_outlined, city),
            if (height != null && height > 0) _Fact(Icons.height, '$height см'),
            if (zodiac != null && zodiac.isNotEmpty)
              _Fact(Icons.brightness_3_outlined, zodiac),
          ],
        ),
        if (about != null && about.isNotEmpty)
          _Section(
              title: 'О себе',
              child: Text(about, style: theme.textTheme.bodyLarge)),
        if (status != null && status.isNotEmpty) ...[
          const SizedBox(height: Gaps.md),
          Container(
            padding: const EdgeInsets.all(Gaps.md),
            decoration: BoxDecoration(
              color: scheme.primary.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(Radii.md),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.wb_sunny_outlined, size: 20, color: scheme.primary),
                const SizedBox(width: Gaps.sm),
                Expanded(
                  child: Text(
                    status,
                    style: theme.textTheme.bodyMedium
                        ?.copyWith(fontStyle: FontStyle.italic),
                  ),
                ),
              ],
            ),
          ),
        ],
        if (profile.goals.isNotEmpty)
          _Section(
            title: 'Цели знакомства',
            child:
                _Chips([for (final goal in profile.goals) goal.displayLabel]),
          ),
        if (profile.interests.isNotEmpty)
          _Section(
            title: 'Интересы',
            child: _InterestChips(profile.interests),
          ),
        if (profile.artists.isNotEmpty)
          _Section(
            title: 'Музыка',
            child: _Chips([for (final artist in profile.artists) artist.name],
                icon: Icons.music_note_rounded),
          ),
        if (profile.communities.isNotEmpty)
          _Section(
            title: 'Группы',
            child: Wrap(
              spacing: Gaps.sm,
              runSpacing: Gaps.sm,
              children: [
                // Groups I am in too: tinted like shared cards.
                for (final group in profile.communities)
                  Chip(
                    label: Text(group.shared ? '${group.display} · у вас тоже' : group.display),
                    backgroundColor: group.shared ? Theme.of(context).colorScheme.primaryContainer : null,
                    visualDensity: VisualDensity.compact,
                  ),
              ],
            ),
          ),
        for (final (title, keys) in _factSections)
          if (options case final o? when keys.any(profile.facts.values.containsKey))
            _Section(
              title: title,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final key in keys)
                    if (o.describe(profile.facts, key) case final text?)
                      Padding(
                        padding: const EdgeInsets.only(bottom: Gaps.xs),
                        child: Text.rich(TextSpan(children: [
                          TextSpan(
                              text: '${Facts.titles[key]}: ',
                              style: TextStyle(color: scheme.onSurfaceVariant)),
                          TextSpan(text: text),
                        ])),
                      ),
                ],
              ),
            ),
        if (profile.tags.isNotEmpty)
          _Section(
            title: 'Общение',
            child: LayoutBuilder(
              builder: (context, constraints) {
                final width = (constraints.maxWidth - Gaps.sm) / 2;
                return Wrap(
                  spacing: Gaps.sm,
                  runSpacing: Gaps.sm,
                  children: [
                    for (final tag in profile.tags)
                      SizedBox(
                        width: width,
                        child: _TagCard(
                            category: tag.categoryLabel,
                            label: tag.label,
                            shared: profile.sharedTags.contains(tag.code)),
                      ),
                  ],
                );
              },
            ),
          ),
      ],
    );
  }
}

const List<(String, List<String>)> _factSections = [
  ('Образ жизни', ['kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'pets']),
  ('Работа и учёба', ['profession', 'educationLevel', 'university', 'languages']),
];

/// Photos switched by swipe or by tapping the left / right third, with
/// segment indicators on top.
class PhotoCarousel extends StatefulWidget {
  const PhotoCarousel({super.key, required this.urls});

  /// Absolute URLs; null or an empty list shows a placeholder.
  final List<String?> urls;

  @override
  State<PhotoCarousel> createState() => _PhotoCarouselState();
}

class _PhotoCarouselState extends State<PhotoCarousel> {
  final PageController _controller = PageController();
  int _index = 0;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _onTapUp(TapUpDetails details, double width) {
    final count = widget.urls.length;
    final x = details.localPosition.dx;
    final target = x < width / 3
        ? _index - 1
        : x > width * 2 / 3
            ? _index + 1
            : _index;
    if (target < 0 || target >= count || target == _index) return;
    _controller.animateToPage(target,
        duration: const Duration(milliseconds: 220), curve: Curves.easeOut);
  }

  @override
  Widget build(BuildContext context) {
    final urls = widget.urls;
    return ClipRRect(
      borderRadius: BorderRadius.circular(Radii.lg),
      child: AspectRatio(
        aspectRatio: 4 / 5,
        child: urls.isEmpty
            ? const NetworkPhoto(url: null)
            : LayoutBuilder(
                builder: (context, constraints) => GestureDetector(
                  onTapUp: (d) => _onTapUp(d, constraints.maxWidth),
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      PageView.builder(
                        controller: _controller,
                        itemCount: urls.length,
                        onPageChanged: (i) => setState(() => _index = i),
                        itemBuilder: (context, i) => NetworkPhoto(url: urls[i]),
                      ),
                      if (urls.length > 1)
                        Positioned(
                          top: 10,
                          left: 12,
                          right: 12,
                          child:
                              PhotoSegments(count: urls.length, index: _index),
                        ),
                    ],
                  ),
                ),
              ),
      ),
    );
  }
}

/// Thin white bars over a photo: which of [count] photos is shown.
class PhotoSegments extends StatelessWidget {
  const PhotoSegments({super.key, required this.count, required this.index});

  final int count;
  final int index;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          for (var i = 0; i < count; i++)
            Expanded(
              child: Container(
                height: 4,
                margin: const EdgeInsets.symmetric(horizontal: 2),
                decoration: BoxDecoration(
                  color: i == index
                      ? Colors.white
                      : Colors.white.withValues(alpha: 0.38),
                  borderRadius: BorderRadius.circular(2),
                  boxShadow: const [
                    BoxShadow(color: Color(0x33000000), blurRadius: 2)
                  ],
                ),
              ),
            ),
        ],
      );
}

class _Fact extends StatelessWidget {
  const _Fact(this.icon, this.text, {this.color});

  final IconData icon;
  final String text;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final c = color ?? theme.colorScheme.onSurfaceVariant;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 18, color: c),
        const SizedBox(width: Gaps.xs),
        Text(text,
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: c, fontWeight: FontWeight.w600)),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: Gaps.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: theme.textTheme.titleMedium),
          const SizedBox(height: Gaps.sm),
          child,
        ],
      ),
    );
  }
}

/// Interests; one with a comment opens it under the chips on a tap (tap again to close), others do nothing.
class _InterestChips extends StatefulWidget {
  const _InterestChips(this.interests);

  final List<Interest> interests;

  @override
  State<_InterestChips> createState() => _InterestChipsState();
}

class _InterestChipsState extends State<_InterestChips> {
  String? _open;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final open = widget.interests.where((i) => i.code == _open && i.comment != null).firstOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: Gaps.sm,
          runSpacing: Gaps.sm,
          children: [
            for (final interest in widget.interests)
              interest.comment == null
                  ? Chip(label: Text(interest.display), visualDensity: VisualDensity.compact)
                  : ChoiceChip(
                      chipAnimationStyle: instantChip,
                      label: Text(interest.display),
                      // A small mark: this one has something to tell.
                      avatar: Icon(Icons.chat_bubble_outline_rounded, size: 14, color: scheme.primary),
                      showCheckmark: false,
                      selected: _open == interest.code,
                      visualDensity: VisualDensity.compact,
                      onSelected: (on) => setState(() => _open = on ? interest.code : null),
                    ),
          ],
        ),
        if (open != null)
          Padding(
            padding: const EdgeInsets.only(top: Gaps.sm),
            child: Text.rich(TextSpan(children: [
              TextSpan(text: '${open.display} — ', style: const TextStyle(fontWeight: FontWeight.w600)),
              TextSpan(text: open.comment),
            ])),
          ),
      ],
    );
  }
}

class _Chips extends StatelessWidget {
  const _Chips(this.labels, {this.icon});

  final List<String> labels;
  final IconData? icon;

  @override
  Widget build(BuildContext context) => Wrap(
        spacing: Gaps.sm,
        runSpacing: Gaps.sm,
        children: [
          for (final label in labels)
            Chip(
              avatar: icon == null
                  ? null
                  : Icon(icon,
                      size: 16, color: Theme.of(context).colorScheme.primary),
              label: Text(label),
              visualDensity: VisualDensity.compact,
            ),
        ],
      );
}

class _TagCard extends StatelessWidget {
  const _TagCard({required this.category, required this.label, this.shared = false});

  final String category;
  final String label;

  /// I chose this card too: a pale rose tint and «у вас тоже».
  final bool shared;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: shared ? scheme.primaryContainer : scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(Radii.md),
        border: shared ? null : Border.all(color: scheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (category.isNotEmpty)
            Text(category,
                style: theme.textTheme.labelSmall
                    ?.copyWith(color: scheme.onSurfaceVariant)),
          Text(label,
              style: theme.textTheme.titleSmall
                  ?.copyWith(color: shared ? scheme.onPrimaryContainer : null)),
          if (shared)
            Text('у вас тоже',
                style: theme.textTheme.labelSmall?.copyWith(color: scheme.onPrimaryContainer)),
        ],
      ),
    );
  }
}

/// A signed photo URL with a placeholder (missing or expired link).
class NetworkPhoto extends StatelessWidget {
  const NetworkPhoto({super.key, required this.url});

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
      // Keep the previous photo on screen while the next one loads (no see-through frame).
      gaplessPlayback: true,
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
