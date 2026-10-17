import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/api/realtime.dart';
import '../../core/format.dart';
import '../../core/models/social.dart';
import '../discovery/profile_details_sheet.dart';
import '../safety/report_sheet.dart';
import 'chats_controller.dart';

/// One conversation: history (older on demand), sending with a retry-safe
/// clientMessageId, read receipts, new messages from the event stream.
class ChatScreen extends StatefulWidget {
  const ChatScreen({
    super.key,
    required this.controller,
    required this.events,
    required this.matchId,
  });

  final ChatsController controller;
  final Stream<RealtimeEvent> events;
  final String matchId;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  static const int _pageSize = 30;

  final TextEditingController _input = TextEditingController();
  late final StreamSubscription<RealtimeEvent> _subscription;

  /// Newest first (as the API pages), shown in a reversed list.
  final List<ChatMessage> _messages = <ChatMessage>[];
  String? _olderCursor;
  bool _loading = true;
  bool _loadingOlder = false;
  bool _sending = false;
  String? _error;

  /// A text that failed to send keeps its id, so a retry is stored once.
  String? _unsentText;
  String? _unsentId;

  ChatsController get _chats => widget.controller;

  @override
  void initState() {
    super.initState();
    _chats.openMatchId = widget.matchId;
    _chats.addListener(_onChatsChanged);
    _subscription = widget.events.listen(_onEvent);
    _load();
  }

  @override
  void dispose() {
    if (_chats.openMatchId == widget.matchId) _chats.openMatchId = null;
    _chats.removeListener(_onChatsChanged);
    _subscription.cancel();
    _input.dispose();
    super.dispose();
  }

  void _onChatsChanged() {
    if (!mounted) return;
    if (_chats.byId(widget.matchId) == null && !_chats.loading) {
      // Unmatched or blocked by the other side.
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Диалог закрыт')));
      Navigator.of(context).maybePop();
      return;
    }
    setState(() {}); // read receipts
  }

  Future<void> _load() async {
    try {
      final page = await _chats.api
          .getMessages(widget.matchId, limit: _pageSize);
      if (!mounted) return;
      setState(() {
        _messages
          ..clear()
          ..addAll(page.items);
        _olderCursor = page.nextBefore;
        _loading = false;
        _error = null;
      });
      _chats.markRead(widget.matchId, page.items.isEmpty ? null : page.items.first);
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.code == 'MATCH_NOT_FOUND') {
        _chats.remove(widget.matchId);
        return;
      }
      setState(() {
        _loading = false;
        _error = describeError(e);
      });
    }
  }

  Future<void> _loadOlder() async {
    final cursor = _olderCursor;
    if (cursor == null || _loadingOlder) return;
    setState(() => _loadingOlder = true);
    try {
      final page = await _chats.api
          .getMessages(widget.matchId, before: cursor, limit: _pageSize);
      if (!mounted) return;
      final known = _messages.map((m) => m.id).toSet();
      setState(() {
        _messages.addAll(page.items.where((m) => !known.contains(m.id)));
        _olderCursor = page.nextBefore;
      });
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _loadingOlder = false);
    }
  }

  void _onEvent(RealtimeEvent event) {
    if (event.name == 'ready') {
      _load();
    } else if (event.name == 'message') {
      final message = ChatMessage.fromJson(event.data);
      if (message.matchId != widget.matchId) return;
      _add(message);
    }
  }

  void _add(ChatMessage message) {
    if (_messages.any((m) => m.id == message.id)) return;
    setState(() => _messages.insert(0, message));
    _chats.markRead(widget.matchId, message);
  }

  Future<void> _send({String? text, String? pack, String? sticker}) async {
    if (_sending) return;
    final String id;
    if (text != null && text == _unsentText && _unsentId != null) {
      id = _unsentId!;
    } else {
      id = '${DateTime.now().microsecondsSinceEpoch}-${math.Random().nextInt(1 << 32)}';
    }
    _unsentText = text;
    _unsentId = id;
    setState(() => _sending = true);
    if (text != null) _input.clear();
    try {
      final message = await _chats.api.sendMessage(
        widget.matchId,
        clientMessageId: id,
        text: text,
        stickerPackCode: pack,
        sticker: sticker,
      );
      _unsentText = null;
      _unsentId = null;
      if (!mounted) return;
      _add(message);
      _chats.addMessage(message);
    } catch (e) {
      if (!mounted) return;
      if (text != null && _input.text.isEmpty) _input.text = text;
      _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  void _sendText() {
    final text = _input.text.trim();
    if (text.isNotEmpty) _send(text: text);
  }

  void _snack(String text) => ScaffoldMessenger.of(context)
      .showSnackBar(SnackBar(content: Text(text)));

  Future<void> _menu(String action) async {
    final match = _chats.byId(widget.matchId);
    if (match == null) return;
    final api = _chats.api;
    final profile = match.profile;
    switch (action) {
      case 'profile':
        final result =
            await showProfileDetails(context, api: api, profile: profile);
        if (result == ProfileDetailsResult.blocked) _chats.remove(match.id);
      case 'report':
        await showReportSheet(context,
            api: api, profileId: profile.id, name: profile.displayName);
      case 'block':
        if (await confirmAndBlock(context,
            api: api, profileId: profile.id, name: profile.displayName)) {
          _chats.remove(match.id);
        }
      case 'unmatch':
        try {
          await api.unmatch(match.id);
          _chats.remove(match.id);
        } catch (e) {
          if (mounted) _snack(describeError(e));
        }
    }
  }

  @override
  Widget build(BuildContext context) {
    final match = _chats.byId(widget.matchId);
    final theme = Theme.of(context);
    // My messages up to the other person's read pointer are read.
    final readIndex = match?.otherLastReadMessageId == null
        ? -1
        : _messages.indexWhere((m) => m.id == match!.otherLastReadMessageId);

    return Scaffold(
      appBar: AppBar(
        title: Text(match?.profile.displayName ?? 'Чат'),
        actions: [
          PopupMenuButton<String>(
            onSelected: _menu,
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'profile', child: Text('Анкета')),
              PopupMenuItem(value: 'report', child: Text('Пожаловаться')),
              PopupMenuItem(value: 'block', child: Text('Заблокировать')),
              PopupMenuItem(value: 'unmatch', child: Text('Разорвать пару')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _error != null
                    ? Center(
                        child: TextButton(
                          onPressed: _load,
                          child: Text('${_error!}\nПовторить',
                              textAlign: TextAlign.center),
                        ),
                      )
                    : _messages.isEmpty
                        ? const Center(
                            child: Text('У вас мэтч! Начните разговор 👋'))
                        : ListView.builder(
                            reverse: true,
                            padding: const EdgeInsets.all(12),
                            itemCount:
                                _messages.length + (_olderCursor == null ? 0 : 1),
                            itemBuilder: (context, index) {
                              if (index == _messages.length) {
                                return Center(
                                  child: TextButton(
                                    onPressed: _loadingOlder ? null : _loadOlder,
                                    child: Text(_loadingOlder
                                        ? 'Загрузка…'
                                        : 'Показать более ранние'),
                                  ),
                                );
                              }
                              final message = _messages[index];
                              return _Bubble(
                                message: message,
                                read: message.mine &&
                                    readIndex >= 0 &&
                                    index >= readIndex,
                                onReport: message.mine || match == null
                                    ? null
                                    : () => showReportSheet(
                                          context,
                                          api: _chats.api,
                                          profileId: match.profile.id,
                                          name: match.profile.displayName,
                                          messageId: message.id,
                                        ),
                              );
                            },
                          ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 4, 4, 8),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _input,
                      minLines: 1,
                      maxLines: 4,
                      maxLength: 2000,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: const InputDecoration(
                        hintText: 'Сообщение',
                        counterText: '',
                        border: OutlineInputBorder(),
                        isDense: true,
                      ),
                      onSubmitted: (_) => _sendText(),
                    ),
                  ),
                  IconButton(
                    tooltip: 'Отправить',
                    color: theme.colorScheme.primary,
                    onPressed: _sending ? null : _sendText,
                    icon: const Icon(Icons.send),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.message, required this.read, this.onReport});

  final ChatMessage message;
  final bool read;
  final VoidCallback? onReport;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final mine = message.mine;
    final time = message.createdAt;
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: GestureDetector(
        onLongPress: onReport,
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 3),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.75,
          ),
          decoration: BoxDecoration(
            color: mine
                ? theme.colorScheme.primary
                : theme.colorScheme.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                message.isSticker
                    ? '✨ ${message.sticker ?? ''}'
                    : (message.text ?? ''),
                style: TextStyle(
                  color: mine ? theme.colorScheme.onPrimary : null,
                  fontSize: message.isSticker ? 22 : 15,
                ),
              ),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (time != null)
                    Text(
                      formatDateTime(time).substring(11),
                      style: TextStyle(
                        fontSize: 10,
                        color: mine ? Colors.white70 : Colors.black45,
                      ),
                    ),
                  if (mine)
                    Padding(
                      padding: const EdgeInsets.only(left: 4),
                      child: Icon(
                        read ? Icons.done_all : Icons.done,
                        size: 14,
                        color: Colors.white70,
                      ),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
