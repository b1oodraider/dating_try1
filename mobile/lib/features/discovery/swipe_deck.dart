import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/models/public_profile.dart';
import '../../core/theme/app_theme.dart';
import '../premium/premium_badge.dart';
import 'profile_card.dart';

enum SwipeAction { like, pass, superlike }

/// Tinder-style deck: drag the top card right (like), left (pass) or up
/// (super like), or use the buttons. [onSwipe] is called once the card has
/// flown out; the caller removes it from [profiles] right away.
class SwipeDeck extends StatefulWidget {
  const SwipeDeck({
    super.key,
    required this.profiles,
    required this.photoUrl,
    required this.onSwipe,
    required this.onOpenDetails,
    this.likesBlocked = false,
    this.onLikeBlocked,
  });

  final List<PublicProfile> profiles;

  /// Resolves a relative signed photo path to an absolute URL.
  final String Function(String url) photoUrl;
  final void Function(PublicProfile profile, SwipeAction action) onSwipe;
  final void Function(PublicProfile profile) onOpenDetails;

  /// Likes are restricted: like / super like are disabled and those swipes spring back.
  final bool likesBlocked;

  /// A like was tried while [likesBlocked].
  final VoidCallback? onLikeBlocked;

  @override
  State<SwipeDeck> createState() => SwipeDeckState();
}

class SwipeDeckState extends State<SwipeDeck>
    with SingleTickerProviderStateMixin {
  late final AnimationController _fly = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 220),
  );

  Offset _drag = Offset.zero;
  Offset _flyFrom = Offset.zero;
  Offset _flyTo = Offset.zero;
  SwipeAction? _flying;
  int _photoIndex = 0;
  String? _topId;

  @override
  void dispose() {
    _fly.dispose();
    super.dispose();
  }

  PublicProfile? get _top =>
      widget.profiles.isEmpty ? null : widget.profiles.first;

  /// Buttons and keyboard-free callers swipe through here.
  Future<void> swipe(SwipeAction action) async {
    final profile = _top;
    if (profile == null || _flying != null) return;
    if (widget.likesBlocked && action != SwipeAction.pass) {
      setState(() => _drag = Offset.zero);
      widget.onLikeBlocked?.call();
      return;
    }
    final size = context.size ?? const Size(400, 600);
    _flyFrom = _drag;
    _flyTo = switch (action) {
      SwipeAction.like => Offset(size.width * 1.5, _drag.dy + 40),
      SwipeAction.pass => Offset(-size.width * 1.5, _drag.dy + 40),
      SwipeAction.superlike => Offset(_drag.dx, -size.height * 1.3),
    };
    setState(() => _flying = action);
    await _fly.forward(from: 0);
    if (!mounted) return;
    setState(() {
      _flying = null;
      _drag = Offset.zero;
    });
    widget.onSwipe(profile, action);
  }

  void _onPanUpdate(DragUpdateDetails details) {
    if (_flying != null) return;
    setState(() => _drag += details.delta);
  }

  void _onPanEnd(DragEndDetails details) {
    if (_flying != null) return;
    final width = context.size?.width ?? 400;
    final velocity = details.velocity.pixelsPerSecond;
    if (_drag.dy < -120 && _drag.dx.abs() < 80) {
      swipe(SwipeAction.superlike);
    } else if (_drag.dx.abs() > width * 0.28 || velocity.dx.abs() > 900) {
      swipe((_drag.dx + velocity.dx * 0.1) > 0
          ? SwipeAction.like
          : SwipeAction.pass);
    } else {
      setState(() => _drag = Offset.zero);
    }
  }

  void _onTapUp(TapUpDetails details, PublicProfile profile, double width) {
    final photos = profile.photos.length;
    if (photos < 2) return;
    setState(() {
      _photoIndex = details.localPosition.dx < width / 2
          ? (_photoIndex - 1 + photos) % photos
          : (_photoIndex + 1) % photos;
    });
  }

  /// Loads every photo of the top card ahead, so switching photos is instant.
  void _precache(PublicProfile profile) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      for (final photo in profile.photos) {
        precacheImage(NetworkImage(widget.photoUrl(photo.url)), context,
            onError: (_, __) {});
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final top = _top;
    if (top?.id != _topId) {
      _topId = top?.id;
      _photoIndex = 0;
      if (top != null) _precache(top);
    }
    final next = widget.profiles.length > 1 ? widget.profiles[1] : null;

    return Column(
      children: [
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.xs, Gaps.md, 0),
            child: LayoutBuilder(
              builder: (context, constraints) => Stack(
                children: [
                  if (next != null)
                    Positioned.fill(
                      child: Transform.scale(
                        scale: 0.95,
                        child: _CardFace(
                          profile: next,
                          photoIndex: 0,
                          photoUrl: widget.photoUrl,
                        ),
                      ),
                    ),
                  if (top != null)
                    Positioned.fill(
                      child: AnimatedBuilder(
                        animation: _fly,
                        builder: (context, child) {
                          final offset = _flying == null
                              ? _drag
                              : Offset.lerp(_flyFrom, _flyTo,
                                  Curves.easeIn.transform(_fly.value))!;
                          return Transform.translate(
                            offset: offset,
                            child: Transform.rotate(
                              angle: offset.dx / constraints.maxWidth * 0.35,
                              child: child,
                            ),
                          );
                        },
                        child: GestureDetector(
                          onPanUpdate: _onPanUpdate,
                          onPanEnd: _onPanEnd,
                          onTapUp: (d) =>
                              _onTapUp(d, top, constraints.maxWidth),
                          child: Stack(
                            children: [
                              Positioned.fill(
                                child: _CardFace(
                                  profile: top,
                                  photoIndex: _photoIndex,
                                  photoUrl: widget.photoUrl,
                                  onInfo: () => widget.onOpenDetails(top),
                                ),
                              ),
                              _Stamp(
                                text: 'ЛАЙК',
                                color: Brand.like,
                                alignment: Alignment.topLeft,
                                opacity: ((_drag.dx - 30) / 90).clamp(0.0, 1.0),
                              ),
                              _Stamp(
                                text: 'НЕТ',
                                color: Brand.pass,
                                alignment: Alignment.topRight,
                                opacity:
                                    ((-_drag.dx - 30) / 90).clamp(0.0, 1.0),
                              ),
                              _Stamp(
                                text: 'СУПЕРЛАЙК',
                                color: Colors.white,
                                alignment: Alignment.bottomCenter,
                                opacity: _drag.dx.abs() < 80
                                    ? ((-_drag.dy - 40) / 90).clamp(0.0, 1.0)
                                    : 0,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
        Padding(
          padding:
              const EdgeInsets.fromLTRB(Gaps.md, Gaps.md, Gaps.md, Gaps.md),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              _RoundButton(
                icon: Icons.close_rounded,
                color: Brand.pass,
                tooltip: 'Пропустить',
                size: 64,
                onPressed: top == null ? null : () => swipe(SwipeAction.pass),
              ),
              _RoundButton(
                icon: Icons.star_rounded,
                tooltip: 'Суперлайк',
                size: 52,
                onPressed: top == null || widget.likesBlocked
                    ? null
                    : () => swipe(SwipeAction.superlike),
              ),
              _RoundButton(
                icon: Icons.favorite_rounded,
                color: Brand.like,
                tooltip: 'Нравится',
                size: 64,
                filled: true,
                onPressed: top == null || widget.likesBlocked
                    ? null
                    : () => swipe(SwipeAction.like),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _CardFace extends StatelessWidget {
  const _CardFace({
    required this.profile,
    required this.photoIndex,
    required this.photoUrl,
    this.onInfo,
  });

  final PublicProfile profile;
  final int photoIndex;
  final String Function(String url) photoUrl;
  final VoidCallback? onInfo;

  @override
  Widget build(BuildContext context) {
    final photos = profile.photos;
    final photo =
        photos.isEmpty ? null : photos[math.min(photoIndex, photos.length - 1)];
    final details = [
      if (profile.distanceKm != null)
        '~${profile.distanceKm} км'
      else if ((profile.city ?? '').isNotEmpty)
        profile.city!,
      if (profile.heightCm != null) '${profile.heightCm} см',
    ].join(' · ');

    final theme = Theme.of(context);
    final pace = profile.tags.where((t) => t.category == 'pace').firstOrNull;
    return ClipRRect(
      borderRadius: BorderRadius.circular(Radii.card),
      child: Stack(
        fit: StackFit.expand,
        children: [
          // Opaque base: the card below must never show through a loading photo.
          const ColoredBox(color: Colors.black),
          NetworkPhoto(url: photo == null ? null : photoUrl(photo.url)),
          if (photos.length > 1)
            Positioned(
              top: 10,
              left: 12,
              right: 12,
              child: PhotoSegments(count: photos.length, index: photoIndex),
            ),
          if (profile.compatibility != null)
            Positioned(
              top: 24,
              left: 12,
              child: _Pill(
                '${profile.compatibility}% совпадение',
                color: Brand.rose,
              ),
            ),
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: DecoratedBox(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.bottomCenter,
                  end: Alignment.topCenter,
                  stops: [0, 0.55, 1],
                  colors: [
                    Color(0xE6000000),
                    Color(0x80000000),
                    Colors.transparent
                  ],
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                    Gaps.md + 4, 64, Gaps.sm, Gaps.md + 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Wrap(
                            spacing: 8,
                            crossAxisAlignment: WrapCrossAlignment.center,
                            children: [
                              Text.rich(
                                TextSpan(children: [
                                  TextSpan(
                                    text: profile.displayName,
                                    style: const TextStyle(
                                        fontWeight: FontWeight.w700),
                                  ),
                                  if (profile.age != null)
                                    TextSpan(
                                      text: '  ${profile.age}',
                                      style: const TextStyle(
                                          fontWeight: FontWeight.w400),
                                    ),
                                ]),
                                style: theme.textTheme.headlineMedium?.copyWith(
                                  color: Colors.white,
                                  fontSize: 30,
                                ),
                              ),
                              if (profile.verified)
                                const Tooltip(
                                  message: 'Селфи проверено модератором',
                                  child: Icon(Icons.verified,
                                      color: Colors.white, size: 24),
                                ),
                              if (profile.premium) const PremiumBadge(),
                            ],
                          ),
                          if (details.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Row(
                                children: [
                                  const Icon(Icons.place_outlined,
                                      size: 16, color: Colors.white70),
                                  const SizedBox(width: 4),
                                  Flexible(
                                    child: Text(
                                      details,
                                      overflow: TextOverflow.ellipsis,
                                      style: theme.textTheme.bodyMedium
                                          ?.copyWith(color: Colors.white70),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          if ((profile.dailyStatusText ?? '').isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: Gaps.sm),
                              child: Text(
                                '«${profile.dailyStatusText}»',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: theme.textTheme.bodyMedium?.copyWith(
                                  color: Colors.white,
                                  fontStyle: FontStyle.italic,
                                ),
                              ),
                            ),
                          // The product's own thing: how this person likes to talk (the pace card).
                          if (pace != null)
                            Padding(
                              padding: const EdgeInsets.only(top: Gaps.sm + 2),
                              child: Text.rich(
                                TextSpan(children: [
                                  const TextSpan(
                                      text: 'Темп  ',
                                      style: TextStyle(color: Colors.white70)),
                                  TextSpan(text: pace.label),
                                ]),
                                style: theme.textTheme.titleSmall
                                    ?.copyWith(color: Colors.white),
                              ),
                            ),
                          if (profile.common.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: Gaps.sm + 2),
                              child: Wrap(
                                spacing: 6,
                                runSpacing: 6,
                                crossAxisAlignment: WrapCrossAlignment.center,
                                children: [
                                  Text(
                                    'Общее:',
                                    style: theme.textTheme.labelLarge
                                        ?.copyWith(color: Colors.white70),
                                  ),
                                  for (final thing in profile.common.take(2))
                                    _Pill(thing, icon: Icons.favorite_rounded),
                                ],
                              ),
                            ),
                        ],
                      ),
                    ),
                    if (onInfo != null)
                      IconButton(
                        tooltip: 'Подробнее',
                        onPressed: onInfo,
                        style: IconButton.styleFrom(
                          backgroundColor: Colors.white.withValues(alpha: 0.18),
                        ),
                        icon: const Icon(Icons.keyboard_arrow_up_rounded,
                            color: Colors.white, size: 28),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, {this.icon, this.color});

  final String text;
  final IconData? icon;
  final Color? color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: color ?? Colors.white.withValues(alpha: 0.2),
          borderRadius: BorderRadius.circular(100),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (icon != null) ...[
              Icon(icon, size: 14, color: Colors.white),
              const SizedBox(width: 4),
            ],
            Text(text,
                style: const TextStyle(
                    color: Colors.white,
                    fontSize: 13,
                    fontWeight: FontWeight.w600)),
          ],
        ),
      );
}

class _Stamp extends StatelessWidget {
  const _Stamp({
    required this.text,
    required this.color,
    required this.alignment,
    required this.opacity,
  });

  final String text;
  final Color color;
  final Alignment alignment;
  final double opacity;

  @override
  Widget build(BuildContext context) {
    if (opacity <= 0) return const SizedBox.shrink();
    return Align(
      alignment: alignment,
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Opacity(
          opacity: opacity,
          child: Transform.rotate(
            angle: alignment == Alignment.topLeft
                ? -0.25
                : alignment == Alignment.topRight
                    ? 0.25
                    : 0,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.15),
                border: Border.all(color: color, width: 4),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                text,
                style: TextStyle(
                  color: color,
                  fontSize: 30,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 1,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Round action button: white (surface) disc with a coloured icon and a soft shadow; [filled] uses the
/// brand gradient instead (the main action).
class _RoundButton extends StatelessWidget {
  const _RoundButton({
    required this.icon,
    this.color,
    required this.tooltip,
    required this.onPressed,
    required this.size,
    this.filled = false,
  });

  final IconData icon;

  /// Icon colour; null = the ink of the theme.
  final Color? color;
  final String tooltip;
  final VoidCallback? onPressed;
  final double size;
  final bool filled;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final enabled = onPressed != null;
    return Tooltip(
      message: tooltip,
      child: Semantics(
        button: true,
        label: tooltip,
        enabled: enabled,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 150),
          opacity: enabled ? 1 : 0.45,
          child: DecoratedBox(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: filled ? Brand.like : scheme.surfaceContainerLowest,
              border: filled ? null : Border.all(color: scheme.outlineVariant),
            ),
            child: Material(
              type: MaterialType.transparency,
              shape: const CircleBorder(),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: onPressed,
                child: SizedBox(
                  width: size,
                  height: size,
                  child: Icon(icon,
                      size: size * 0.5,
                      color: filled ? Colors.white : color ?? scheme.onSurface),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
