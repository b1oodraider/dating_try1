import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/api/realtime.dart';
import '../../core/models/social.dart';

/// My matches (most recent activity first) kept current from the event
/// stream; drives the chat list and the unread badge.
class ChatsController extends ChangeNotifier {
  ChatsController(this.api, Stream<RealtimeEvent> events) {
    _subscription = events.listen(_onEvent);
  }

  final DatingApiClient api;
  late final StreamSubscription<RealtimeEvent> _subscription;

  List<Match> matches = const <Match>[];
  bool loading = true;
  String? error;

  /// The chat on screen: its new messages are not counted as unread.
  String? openMatchId;

  int get unreadChats => matches.where((m) => m.unreadCount > 0).length;

  Match? byId(String id) {
    for (final match in matches) {
      if (match.id == id) return match;
    }
    return null;
  }

  Future<void> load() async {
    try {
      matches = await api.getMatches();
      error = null;
    } catch (e) {
      if (e is ApiException && e.isUnauthorized) return;
      error = describeError(e);
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  /// A message I sent or received (also from the event stream).
  void addMessage(ChatMessage message) {
    final match = byId(message.matchId);
    if (match == null) {
      load();
      return;
    }
    final unread = !message.mine && message.matchId != openMatchId;
    matches = [
      match.copyWith(
        lastMessage: message,
        unreadCount: match.unreadCount + (unread ? 1 : 0),
      ),
      ...matches.where((m) => m.id != message.matchId),
    ];
    notifyListeners();
  }

  /// Moves my read pointer to [newest] when it came from the other person.
  void markRead(String matchId, ChatMessage? newest) {
    if (newest == null || newest.mine) return;
    final match = byId(matchId);
    if (match == null || match.myLastReadMessageId == newest.id) return;
    matches = [
      for (final m in matches)
        m.id == matchId
            ? m.copyWith(unreadCount: 0, myLastReadMessageId: newest.id)
            : m,
    ];
    notifyListeners();
    api.markRead(matchId, newest.id).catchError((Object e) {
      debugPrint('markRead failed: $e');
    });
  }

  void remove(String matchId) {
    matches = matches.where((m) => m.id != matchId).toList();
    notifyListeners();
  }

  void _onEvent(RealtimeEvent event) {
    switch (event.name) {
      case 'ready':
        load();
      case 'message':
        addMessage(ChatMessage.fromJson(event.data));
      case 'match':
        final match = Match.fromJson(event.data);
        if (byId(match.id) == null) {
          matches = [match, ...matches];
          notifyListeners();
        }
      case 'match_closed':
        final id = event.data['matchId'];
        if (id is String) remove(id);
      case 'read':
        final id = event.data['matchId'];
        final last = event.data['lastReadMessageId'];
        if (id is String && last is String) {
          matches = [
            for (final m in matches)
              m.id == id ? m.copyWith(otherLastReadMessageId: last) : m,
          ];
          notifyListeners();
        }
    }
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
