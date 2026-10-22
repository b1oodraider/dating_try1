import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../core/widgets/empty_state.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/api/realtime.dart';
import '../../core/models/social.dart';
import '../chat/match_dialog.dart';
import '../premium/purchase_screen.dart';
import '../discovery/profile_card.dart';
import '../discovery/profile_details_sheet.dart';

/// "Who liked me": answer with a like (match) or skip.
class LikesScreen extends StatefulWidget {
  const LikesScreen({
    super.key,
    required this.api,
    required this.events,
    required this.onOpenChat,
  });

  final DatingApiClient api;
  final Stream<RealtimeEvent> events;
  final void Function(String matchId) onOpenChat;

  @override
  State<LikesScreen> createState() => _LikesScreenState();
}

class _LikesScreenState extends State<LikesScreen> {
  static const int _pageSize = 50;

  final List<ReceivedLike> _likes = <ReceivedLike>[];
  late final StreamSubscription<RealtimeEvent> _subscription;
  int _total = 0;
  bool _loading = true;
  bool _loadingMore = false;
  bool _premiumRequired = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _subscription = widget.events.listen((event) {
      if (event.name == 'like_received' || event.name == 'ready') _load();
    });
    _load();
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final page = await widget.api.getReceivedLikes(limit: _pageSize);
      if (!mounted) return;
      setState(() {
        _likes
          ..clear()
          ..addAll(page.items);
        _total = page.totalElements;
        _loading = false;
        _premiumRequired = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _premiumRequired = e is ApiException && e.isPremiumRequired;
        _error = describeError(e);
      });
    }
  }

  /// Answered likes leave the server's list too, so the likes shown are its
  /// first entries: the page holding entry `_likes.length` overlaps them a
  /// little (deduplicated) and skips nothing.
  Future<void> _loadMore() async {
    setState(() => _loadingMore = true);
    try {
      final page = await widget.api.getReceivedLikes(
          page: _likes.length ~/ _pageSize, limit: _pageSize);
      if (!mounted) return;
      final known = _likes.map((l) => l.profile.id).toSet();
      setState(() {
        _likes.addAll(page.items.where((l) => !known.contains(l.profile.id)));
        _total = page.totalElements;
      });
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  Future<void> _answer(ReceivedLike like, bool likeBack) async {
    final profile = like.profile;
    setState(() {
      _likes.remove(like);
      _total = _total > 0 ? _total - 1 : 0;
    });
    try {
      final result = likeBack
          ? await widget.api.like(profile.id)
          : await widget.api.pass(profile.id);
      if (!mounted) return;
      final matchId = result.matchId;
      if (result.matched && matchId != null) {
        showMatchDialog(
          context,
          profile: profile,
          photoUrl: widget.api.resolveMediaUrl,
          onOpenChat: () => widget.onOpenChat(matchId),
        );
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _likes.insert(0, like);
        _total++;
      });
      _snack(describeError(e));
    }
  }

  Widget _card(int index) => _LikeCard(
        like: _likes[index],
        photoUrl: widget.api.resolveMediaUrl,
        onOpen: () => showProfileDetails(context, api: widget.api, profile: _likes[index].profile),
        onPass: () => _answer(_likes[index], false),
        onLike: () => _answer(_likes[index], true),
      );

  void _snack(String text) => ScaffoldMessenger.of(context)
      .showSnackBar(SnackBar(content: Text(text)));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_total > 0 ? 'Симпатии · $_total' : 'Симпатии'),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_premiumRequired) {
      return EmptyState(
        icon: Icons.lock_outline_rounded,
        title: 'Кто вас лайкнул — с премиумом',
        message: 'Без премиума эти люди всё равно встретятся вам в ленте.',
        actions: [
          FilledButton(
            onPressed: () async {
              final bought = await openPurchase(context, api: widget.api, kind: PurchaseKind.premium);
              if (bought && mounted) _load();
            },
            child: const Text('Подключить премиум'),
          ),
        ],
      );
    }
    final error = _error;
    if (error != null) {
      return EmptyState(
        icon: Icons.wifi_off_rounded,
        title: 'Не удалось загрузить',
        message: error,
        actions: [FilledButton(onPressed: _load, child: const Text('Повторить'))],
      );
    }
    if (_likes.isEmpty) {
      return RefreshIndicator(
        onRefresh: _load,
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            child: SizedBox(
              height: constraints.maxHeight,
              child: const EmptyState(
                icon: Icons.favorite_border_rounded,
                title: 'Пока новых симпатий нет',
                message: 'Заполненная анкета с несколькими фото собирает больше лайков.',
              ),
            ),
          ),
        ),
      );
    }
    final more = _likes.length < _total;
    return RefreshIndicator(
      onRefresh: _load,
      child: CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.md),
            sliver: SliverGrid.builder(
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
                childAspectRatio: 0.68,
              ),
              // An odd last card gets its own full-width row below, never an orphan cell.
              itemCount: _likes.length - _likes.length % 2,
              itemBuilder: (context, index) => _card(index),
            ),
          ),
          if (_likes.length.isOdd)
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(Gaps.md, 0, Gaps.md, Gaps.md),
              sliver: SliverToBoxAdapter(
                child: LayoutBuilder(
                  // Same height as a grid card: half the width (minus the gap) / 0.68.
                  builder: (context, c) => SizedBox(
                    height: (c.maxWidth - 12) / 2 / 0.68,
                    child: _card(_likes.length - 1),
                  ),
                ),
              ),
            ),
          if (more)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.only(bottom: Gaps.lg),
                child: Center(
                  child: TextButton(
                    onPressed: _loadingMore ? null : _loadMore,
                    child: Text(_loadingMore ? 'Загрузка…' : 'Показать ещё'),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// A person who liked me: photo with name, and «no» / «like back» right on the card.
class _LikeCard extends StatelessWidget {
  const _LikeCard({
    required this.like,
    required this.photoUrl,
    required this.onOpen,
    required this.onPass,
    required this.onLike,
  });

  final ReceivedLike like;
  final String Function(String url) photoUrl;
  final VoidCallback onOpen;
  final VoidCallback onPass;
  final VoidCallback onLike;

  @override
  Widget build(BuildContext context) {
    final profile = like.profile;
    final photo = profile.primaryPhoto;
    final theme = Theme.of(context);
    return ClipRRect(
      borderRadius: BorderRadius.circular(Radii.lg),
      child: Material(
        color: Colors.black,
        child: InkWell(
          onTap: onOpen,
          child: Stack(
            fit: StackFit.expand,
            children: [
              NetworkPhoto(url: photo == null ? null : photoUrl(photo.url)),
              const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.bottomCenter,
                    end: Alignment.center,
                    colors: [Color(0xD9000000), Colors.transparent],
                  ),
                ),
              ),
              if (like.superlike)
                Positioned(
                  top: Gaps.sm,
                  left: Gaps.sm,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.55),
                      borderRadius: BorderRadius.circular(100),
                    ),
                    child: const Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.star_rounded, size: 14, color: Colors.white),
                        SizedBox(width: 2),
                        Text('Суперлайк',
                            style: TextStyle(
                                color: Colors.white, fontSize: 12, fontWeight: FontWeight.w700)),
                      ],
                    ),
                  ),
                ),
              Positioned(
                left: Gaps.sm + 4,
                right: Gaps.sm,
                bottom: Gaps.sm,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            [
                              profile.displayName,
                              if (profile.age != null) '${profile.age}',
                            ].join(', '),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleMedium?.copyWith(color: Colors.white),
                          ),
                        ),
                        if (profile.verified)
                          const Padding(
                            padding: EdgeInsets.only(left: 4),
                            child: Icon(Icons.verified, size: 16, color: Colors.white),
                          ),
                      ],
                    ),
                    if (profile.compatibility != null)
                      Text('${profile.compatibility}% совпадение',
                          style: theme.textTheme.bodySmall?.copyWith(color: Colors.white70)),
                    const SizedBox(height: Gaps.sm),
                    Row(
                      children: [
                        Expanded(
                          child: _CardAction(
                            icon: Icons.close_rounded,
                            tooltip: 'Пропустить',
                            onPressed: onPass,
                          ),
                        ),
                        const SizedBox(width: Gaps.sm),
                        Expanded(
                          child: _CardAction(
                            icon: Icons.favorite_rounded,
                            tooltip: 'Ответить взаимностью',
                            onPressed: onLike,
                            color: Brand.rose,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CardAction extends StatelessWidget {
  const _CardAction({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.color,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;
  final Color? color;

  @override
  Widget build(BuildContext context) => Tooltip(
        message: tooltip,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: color ?? Colors.white.withValues(alpha: 0.22),
            borderRadius: BorderRadius.circular(100),
          ),
          child: Material(
            type: MaterialType.transparency,
            shape: const StadiumBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: onPressed,
              child: SizedBox(
                height: 40,
                child: Icon(icon, color: Colors.white, size: 22, semanticLabel: tooltip),
              ),
            ),
          ),
        ),
      );
}
