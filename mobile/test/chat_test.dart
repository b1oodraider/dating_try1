import 'dart:async';
import 'dart:convert';

import 'package:dating_mobile/core/api/api_client.dart';
import 'package:dating_mobile/core/api/realtime.dart';
import 'package:dating_mobile/core/models/social.dart';
import 'package:dating_mobile/features/chat/chats_controller.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Map<String, Object?> _match(String id, {int unread = 0}) => {
      'id': id,
      'profile': {'id': 'p-$id', 'displayName': 'Аня $id', 'photos': [], 'tags': []},
      'lastMessage': null,
      'unreadCount': unread,
    };

Map<String, Object?> _message(String id, String matchId, {bool mine = false}) =>
    {'id': id, 'matchId': matchId, 'mine': mine, 'type': 'TEXT', 'text': 'hi'};

void main() {
  test('chat list follows the event stream: unread, order, read, close', () async {
    final reads = <String>[];
    final api = DatingApiClient(
      baseUrl: 'http://api.test/api/v1',
      httpClient: MockClient((request) async {
        if (request.url.path.endsWith('/read')) {
          reads.add(jsonDecode(request.body)['lastReadMessageId'] as String);
          return http.Response('{}', 200);
        }
        return http.Response(jsonEncode([_match('a'), _match('b')]), 200,
            headers: {'content-type': 'application/json'});
      }),
    )..token = 't';
    final events = StreamController<RealtimeEvent>.broadcast();
    final chats = ChatsController(api, events.stream);
    await chats.load();

    chats.openMatchId = 'a';
    events.add(RealtimeEvent('message', _message('m1', 'b')));
    events.add(RealtimeEvent('message', _message('m2', 'a')));
    await Future<void>.delayed(Duration.zero);

    // Newest activity first; only the chat that is not open counts as unread.
    expect(chats.matches.map((m) => m.id), ['a', 'b']);
    expect(chats.byId('b')!.unreadCount, 1);
    expect(chats.byId('a')!.unreadCount, 0);
    expect(chats.unreadChats, 1);

    final m3 = _message('m3', 'b');
    chats.markRead('b', ChatMessage.fromJson(m3));
    chats.markRead('b', ChatMessage.fromJson(m3)); // same pointer: one request
    await Future<void>.delayed(Duration.zero);
    expect(reads, ['m3']);
    expect(chats.unreadChats, 0);

    events.add(const RealtimeEvent('match_closed', {'matchId': 'a'}));
    await Future<void>.delayed(Duration.zero);
    expect(chats.matches.map((m) => m.id), ['b']);

    chats.dispose();
    await events.close();
  });

  test('a reply carries a quote of the answered message', () {
    final message = ChatMessage.fromJson({
      'id': 'r1',
      'matchId': 'a',
      'mine': true,
      'type': 'TEXT',
      'text': 'Кофе',
      'replyTo': {'id': 'q1', 'mine': false, 'type': 'STICKER', 'text': null},
    });
    expect(message.replyTo!.id, 'q1');
    expect(message.replyTo!.mine, isFalse);
    expect(message.replyTo!.preview, 'Стикер');
    expect(ChatMessage.fromJson({'id': 'x', 'matchId': 'a', 'type': 'TEXT'}).replyTo, isNull);
  });
}
