import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/api/realtime.dart';
import '../../core/models/social.dart';
import '../chat/match_dialog.dart';
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
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text(
            'Кто вас лайкнул, видно с премиумом.\n'
            'Без него эти люди встретятся вам в ленте.',
            textAlign: TextAlign.center,
          ),
        ),
      );
    }
    final error = _error;
    if (error != null) {
      return Center(
        child: TextButton(
          onPressed: _load,
          child: Text('$error\nПовторить', textAlign: TextAlign.center),
        ),
      );
    }
    if (_likes.isEmpty) {
      return RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          children: const [
            SizedBox(height: 160),
            Center(child: Text('Пока новых симпатий нет')),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.builder(
        padding: const EdgeInsets.all(8),
        itemCount: _likes.length + (_likes.length < _total ? 1 : 0),
        itemBuilder: (context, index) {
          if (index == _likes.length) {
            return Center(
              child: TextButton(
                onPressed: _loadingMore ? null : _loadMore,
                child: Text(_loadingMore ? 'Загрузка…' : 'Показать ещё'),
              ),
            );
          }
          final like = _likes[index];
          final profile = like.profile;
          final photo = profile.primaryPhoto;
          return Card(
            child: ListTile(
              onTap: () => showProfileDetails(context,
                  api: widget.api, profile: profile),
              leading: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: SizedBox(
                  width: 56,
                  height: 72,
                  child: NetworkPhoto(
                    url: photo == null
                        ? null
                        : widget.api.resolveMediaUrl(photo.url),
                  ),
                ),
              ),
              title: Text([
                profile.displayName,
                if (profile.age != null) '${profile.age}',
              ].join(', ')),
              subtitle: Text([
                if (like.superlike) '⭐ Суперлайк',
                if (profile.compatibility != null)
                  '${profile.compatibility}% совпадение',
                if ((profile.city ?? '').isNotEmpty) profile.city!,
              ].join(' · ')),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    tooltip: 'Пропустить',
                    icon: const Icon(Icons.close),
                    onPressed: () => _answer(like, false),
                  ),
                  IconButton.filled(
                    tooltip: 'Ответить взаимностью',
                    icon: const Icon(Icons.favorite),
                    onPressed: () => _answer(like, true),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
