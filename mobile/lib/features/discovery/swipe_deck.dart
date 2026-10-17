import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/models/public_profile.dart';
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
  });

  final List<PublicProfile> profiles;

  /// Resolves a relative signed photo path to an absolute URL.
  final String Function(String url) photoUrl;
  final void Function(PublicProfile profile, SwipeAction action) onSwipe;
  final void Function(PublicProfile profile) onOpenDetails;

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

  @override
  Widget build(BuildContext context) {
    final top = _top;
    if (top?.id != _topId) {
      _topId = top?.id;
      _photoIndex = 0;
    }
    final next = widget.profiles.length > 1 ? widget.profiles[1] : null;

    return Column(
      children: [
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
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
                              : Offset.lerp(
                                  _flyFrom, _flyTo, Curves.easeIn.transform(_fly.value))!;
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
                                color: Colors.green,
                                alignment: Alignment.topLeft,
                                opacity: ((_drag.dx - 30) / 90).clamp(0.0, 1.0),
                              ),
                              _Stamp(
                                text: 'ПРОПУСК',
                                color: Colors.red,
                                alignment: Alignment.topRight,
                                opacity: ((-_drag.dx - 30) / 90).clamp(0.0, 1.0),
                              ),
                              _Stamp(
                                text: 'СУПЕРЛАЙК',
                                color: Colors.blue,
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
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              _RoundButton(
                icon: Icons.close,
                color: Colors.red,
                tooltip: 'Пропустить',
                onPressed: top == null ? null : () => swipe(SwipeAction.pass),
              ),
              const SizedBox(width: 20),
              _RoundButton(
                icon: Icons.star,
                color: Colors.blue,
                tooltip: 'Суперлайк',
                small: true,
                onPressed:
                    top == null ? null : () => swipe(SwipeAction.superlike),
              ),
              const SizedBox(width: 20),
              _RoundButton(
                icon: Icons.favorite,
                color: Colors.pink,
                tooltip: 'Нравится',
                onPressed: top == null ? null : () => swipe(SwipeAction.like),
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
    final photo = photos.isEmpty
        ? null
        : photos[math.min(photoIndex, photos.length - 1)];
    final details = [
      if ((profile.city ?? '').isNotEmpty) profile.city!,
      if (profile.heightCm != null) '${profile.heightCm} см',
    ].join(' · ');

    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: Stack(
        fit: StackFit.expand,
        children: [
          NetworkPhoto(url: photo == null ? null : photoUrl(photo.url)),
          if (photos.length > 1)
            Positioned(
              top: 8,
              left: 10,
              right: 10,
              child: Row(
                children: [
                  for (var i = 0; i < photos.length; i++)
                    Expanded(
                      child: Container(
                        height: 4,
                        margin: const EdgeInsets.symmetric(horizontal: 2),
                        decoration: BoxDecoration(
                          color: i == photoIndex
                              ? Colors.white
                              : Colors.white.withValues(alpha: 0.4),
                          borderRadius: BorderRadius.circular(2),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          Positioned(
            top: 20,
            right: 12,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (profile.compatibility != null)
                  _Pill('${profile.compatibility}% совпадение'),
                if (profile.verified) const _Pill('✓ Проверен'),
              ],
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
                  colors: [Colors.black87, Colors.transparent],
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 48, 8, 16),
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
                              Text(
                                [
                                  profile.displayName,
                                  if (profile.age != null) '${profile.age}',
                                ].join(', '),
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 26,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              if (profile.premium) const PremiumBadge(),
                            ],
                          ),
                          if (details.isNotEmpty)
                            Text(details,
                                style: const TextStyle(color: Colors.white70)),
                          if ((profile.dailyStatusText ?? '').isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 6),
                              child: Text(
                                '«${profile.dailyStatusText}»',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: Colors.amberAccent,
                                  fontStyle: FontStyle.italic,
                                ),
                              ),
                            ),
                          if (profile.tags.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 8),
                              child: Wrap(
                                spacing: 6,
                                runSpacing: 6,
                                children: [
                                  for (final tag in profile.tags.take(3))
                                    _Pill(tag.label),
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
                        icon: const Icon(Icons.info_outline, color: Colors.white),
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
  const _Pill(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(bottom: 4),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.black54,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(text,
            style: const TextStyle(color: Colors.white, fontSize: 12)),
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
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            decoration: BoxDecoration(
              border: Border.all(color: color, width: 4),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              text,
              style: TextStyle(
                color: color,
                fontSize: 28,
                fontWeight: FontWeight.w900,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _RoundButton extends StatelessWidget {
  const _RoundButton({
    required this.icon,
    required this.color,
    required this.tooltip,
    required this.onPressed,
    this.small = false,
  });

  final IconData icon;
  final Color color;
  final String tooltip;
  final VoidCallback? onPressed;
  final bool small;

  @override
  Widget build(BuildContext context) {
    final size = small ? 52.0 : 64.0;
    return SizedBox(
      width: size,
      height: size,
      child: IconButton.outlined(
        tooltip: tooltip,
        onPressed: onPressed,
        iconSize: small ? 26 : 32,
        color: color,
        icon: Icon(icon),
      ),
    );
  }
}
