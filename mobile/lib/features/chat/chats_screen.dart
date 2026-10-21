import 'package:flutter/material.dart';

import '../../core/widgets/empty_state.dart';

import '../../core/theme/app_theme.dart';
import '../../core/api/realtime.dart';
import '../../core/models/social.dart';
import '../discovery/profile_card.dart';
import 'chat_screen.dart';
import 'chats_controller.dart';

/// My matches; tap opens the chat.
class ChatsScreen extends StatelessWidget {
  const ChatsScreen({
    super.key,
    required this.controller,
    required this.events,
  });

  final ChatsController controller;
  final Stream<RealtimeEvent> events;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Чаты')),
      body: ListenableBuilder(
        listenable: controller,
        builder: (context, _) {
          if (controller.loading) {
            return const Center(child: CircularProgressIndicator());
          }
          final error = controller.error;
          if (controller.matches.isEmpty) {
            return RefreshIndicator(
              onRefresh: controller.load,
              child: LayoutBuilder(
                builder: (context, constraints) => SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  child: SizedBox(
                    height: constraints.maxHeight,
                    child: EmptyState(
                      icon: Icons.chat_bubble_outline_rounded,
                      title: error == null ? 'Пока нет пар' : 'Не удалось загрузить',
                      message: error ??
                          'Ставьте лайки — при взаимной симпатии здесь появится диалог.',
                    ),
                  ),
                ),
              ),
            );
          }
          // New pairs without messages go to the row on top, conversations below.
          final fresh = controller.matches.where((m) => m.lastMessage == null).toList();
          final talks = controller.matches.where((m) => m.lastMessage != null).toList();
          final theme = Theme.of(context);
          return RefreshIndicator(
            onRefresh: controller.load,
            child: ListView(
              padding: const EdgeInsets.only(bottom: Gaps.md),
              children: [
                if (fresh.isNotEmpty) ...[
                  Padding(
                    padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.sm),
                    child: Text('Новые пары', style: theme.textTheme.titleMedium),
                  ),
                  SizedBox(
                    height: 112,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      padding: const EdgeInsets.symmetric(horizontal: Gaps.md),
                      itemCount: fresh.length,
                      separatorBuilder: (_, __) => const SizedBox(width: 14),
                      itemBuilder: (context, index) => _NewPair(
                        match: fresh[index],
                        photoUrl: controller.api.resolveMediaUrl,
                        onTap: () => openChat(context, fresh[index].id),
                      ),
                    ),
                  ),
                ],
                if (talks.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.md, Gaps.md, Gaps.xs),
                    child: Text('Сообщения', style: theme.textTheme.titleMedium),
                  ),
                for (final match in talks)
                  _MatchTile(
                    match: match,
                    onTap: () => openChat(context, match.id),
                    controller: controller,
                  ),
              ],
            ),
          );
        },
      ),
    );
  }

  void openChat(BuildContext context, String matchId) {
    Navigator.of(context).push<void>(MaterialPageRoute<void>(
      builder: (_) => ChatScreen(
        controller: controller,
        events: events,
        matchId: matchId,
      ),
    ));
  }
}

class _MatchTile extends StatelessWidget {
  const _MatchTile({
    required this.match,
    required this.onTap,
    required this.controller,
  });

  final Match match;
  final VoidCallback onTap;
  final ChatsController controller;

  @override
  Widget build(BuildContext context) {
    final profile = match.profile;
    final photo = profile.primaryPhoto;
    final last = match.lastMessage;
    final unread = match.unreadCount > 0;
    final theme = Theme.of(context);
    return ListTile(
      onTap: onTap,
      minVerticalPadding: 12,
      leading: SizedBox(
        width: 56,
        height: 56,
        child: Stack(
          children: [
            ClipOval(
              child: SizedBox(
                width: 56,
                height: 56,
                child: NetworkPhoto(
                  url: photo == null
                      ? null
                      : controller.api.resolveMediaUrl(photo.url),
                ),
              ),
            ),
            if (match.otherOnline)
              Positioned(
                right: 1,
                bottom: 1,
                child: Semantics(
                  label: 'в сети',
                  child: Container(
                    width: 14,
                    height: 14,
                    decoration: BoxDecoration(
                      color: const Color(0xFF16A34A),
                      shape: BoxShape.circle,
                      border: Border.all(color: Theme.of(context).colorScheme.surface, width: 2),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
      title: Row(
        children: [
          Flexible(
            child: Text(profile.displayName, overflow: TextOverflow.ellipsis),
          ),
          if (profile.verified)
            const Padding(
              padding: EdgeInsets.only(left: 4),
              child: Icon(Icons.verified, size: 16, color: Brand.verified),
            ),
        ],
      ),
      subtitle: Text(
        last == null
            ? 'Взаимная симпатия! Напишите первым'
            : last.isSticker
                ? 'Стикер'
                : (last.mine ? 'Вы: ' : '') + (last.text ?? ''),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: unread
            ? theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurface, fontWeight: FontWeight.w700)
            : null,
      ),
      trailing: unread
          ? Badge(
              label: Text('${match.unreadCount}'),
              largeSize: 22,
              padding: const EdgeInsets.symmetric(horizontal: 7),
            )
          : null,
    );
  }
}

/// A new pair without messages: round photo with the brand ring and the name.
class _NewPair extends StatelessWidget {
  const _NewPair({required this.match, required this.photoUrl, required this.onTap});

  final Match match;
  final String Function(String url) photoUrl;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final photo = match.profile.primaryPhoto;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(Radii.md),
      child: SizedBox(
        width: 76,
        child: Column(
          children: [
            Container(
              padding: const EdgeInsets.all(3),
              decoration: const BoxDecoration(shape: BoxShape.circle, color: Brand.rose),
              child: Container(
                padding: const EdgeInsets.all(2),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: Theme.of(context).colorScheme.surface,
                ),
                child: ClipOval(
                  child: SizedBox(
                    width: 64,
                    height: 64,
                    child: NetworkPhoto(url: photo == null ? null : photoUrl(photo.url)),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 6),
            Text(
              match.profile.displayName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelLarge,
            ),
          ],
        ),
      ),
    );
  }
}
