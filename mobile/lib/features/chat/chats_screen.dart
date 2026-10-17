import 'package:flutter/material.dart';

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
              child: ListView(
                children: [
                  const SizedBox(height: 160),
                  Center(
                    child: Text(
                      error ??
                          'Пока нет пар.\nСтавьте лайки — при взаимной '
                              'симпатии здесь появится диалог',
                      textAlign: TextAlign.center,
                    ),
                  ),
                ],
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: controller.load,
            child: ListView.separated(
              itemCount: controller.matches.length,
              separatorBuilder: (_, __) => const Divider(height: 1),
              itemBuilder: (context, index) =>
                  _MatchTile(match: controller.matches[index], onTap: () {
                openChat(context, controller.matches[index].id);
              }, controller: controller),
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
    return ListTile(
      onTap: onTap,
      leading: ClipOval(
        child: SizedBox(
          width: 52,
          height: 52,
          child: NetworkPhoto(
            url: photo == null
                ? null
                : controller.api.resolveMediaUrl(photo.url),
          ),
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
              child: Icon(Icons.verified, size: 16, color: Colors.teal),
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
        style: unread ? const TextStyle(fontWeight: FontWeight.bold) : null,
      ),
      trailing: unread
          ? Badge(label: Text('${match.unreadCount}'))
          : null,
    );
  }
}
