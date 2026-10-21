import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
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

  /// Active MESSAGES (or SUSPENDED) restriction: the composer is replaced by its reason.
  Restriction? _messagesRestriction;

  ChatsController get _chats => widget.controller;

  @override
  void initState() {
    super.initState();
    _chats.openMatchId = widget.matchId;
    _chats.addListener(_onChatsChanged);
    _subscription = widget.events.listen(_onEvent);
    _load();
    _loadRestrictions();
  }

  /// Best effort: on failure the server still refuses with 403 ACCOUNT_RESTRICTED.
  Future<void> _loadRestrictions() async {
    try {
      final all = await _chats.api.getRestrictions();
      if (!mounted) return;
      setState(() => _messagesRestriction = all
          .where((r) => r.kind == 'MESSAGES' || r.kind == 'SUSPENDED')
          .firstOrNull);
    } catch (e) {
      debugPrint('Failed to load restrictions: $e');
    }
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
      final page =
          await _chats.api.getMessages(widget.matchId, limit: _pageSize);
      if (!mounted) return;
      setState(() {
        _messages
          ..clear()
          ..addAll(page.items);
        _olderCursor = page.nextBefore;
        _loading = false;
        _error = null;
      });
      _chats.markRead(
          widget.matchId, page.items.isEmpty ? null : page.items.first);
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
      id =
          '${DateTime.now().microsecondsSinceEpoch}-${math.Random().nextInt(1 << 32)}';
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
      if (e is ApiException && e.code == 'ACCOUNT_RESTRICTED') {
        _loadRestrictions();
      }
      _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  void _sendText() {
    final text = _input.text.trim();
    if (text.isNotEmpty) _send(text: text);
  }

  void _snack(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

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
                            itemCount: _messages.length +
                                (_olderCursor == null ? 0 : 1),
                            itemBuilder: (context, index) {
                              if (index == _messages.length) {
                                return Center(
                                  child: TextButton(
                                    onPressed:
                                        _loadingOlder ? null : _loadOlder,
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
          if (_messagesRestriction case final r?)
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.all(Gaps.md),
                child: Row(
                  children: [
                    Icon(Icons.block, color: theme.colorScheme.error),
                    const SizedBox(width: Gaps.sm),
                    Expanded(
                      child: Text(
                        [
                          r.endsAt == null
                              ? 'Сообщения недоступны до решения модератора'
                              : 'Сообщения недоступны до ${formatDateTime(r.endsAt!)}',
                          if (r.reason.isNotEmpty) r.reason,
                        ].join('. '),
                        style: theme.textTheme.bodyMedium,
                      ),
                    ),
                  ],
                ),
              ),
            )
          else
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                    Gaps.md, Gaps.sm, Gaps.sm, Gaps.sm),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _input,
                        minLines: 1,
                        maxLines: 4,
                        maxLength: 2000,
                        textCapitalization: TextCapitalization.sentences,
                        decoration: InputDecoration(
                          hintText: 'Сообщение',
                          counterText: '',
                          isDense: true,
                          contentPadding: const EdgeInsets.symmetric(
                              horizontal: 18, vertical: 12),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(Radii.lg),
                            borderSide: BorderSide.none,
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(Radii.lg),
                            borderSide: BorderSide.none,
                          ),
                          focusedBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(Radii.lg),
                            borderSide: BorderSide(
                                color: theme.colorScheme.primary, width: 1.5),
                          ),
                        ),
                        onSubmitted: (_) => _sendText(),
                      ),
                    ),
                    const SizedBox(width: Gaps.sm),
                    Tooltip(
                      message: 'Отправить',
                      child: DecoratedBox(
                        decoration: const BoxDecoration(
                          shape: BoxShape.circle,
                          color: Brand.rose,
                        ),
                        child: IconButton(
                          onPressed: _sending ? null : _sendText,
                          color: Colors.white,
                          icon: const Icon(Icons.send_rounded,
                              semanticLabel: 'Отправить'),
                        ),
                      ),
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
          padding: const EdgeInsets.fromLTRB(14, 9, 12, 7),
          constraints: BoxConstraints(
            maxWidth: MediaQuery.of(context).size.width * 0.75,
          ),
          decoration: BoxDecoration(
            color: mine ? Brand.rose : theme.colorScheme.surfaceContainerLow,
            borderRadius: BorderRadius.only(
              topLeft: const Radius.circular(20),
              topRight: const Radius.circular(20),
              bottomLeft: Radius.circular(mine ? 20 : 6),
              bottomRight: Radius.circular(mine ? 6 : 20),
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                message.isSticker
                    ? '✨ ${message.sticker ?? ''}'
                    : (message.text ?? ''),
                style: TextStyle(
                  color: mine ? Colors.white : theme.colorScheme.onSurface,
                  fontSize: message.isSticker ? 22 : 15,
                  height: 1.35,
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
                        color: mine
                            ? Colors.white70
                            : theme.colorScheme.onSurfaceVariant,
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
