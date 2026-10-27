import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/api/realtime.dart';
import '../../core/format.dart';
import '../../core/models/social.dart';
import '../../core/models/date_plan.dart';
import '../dates/date_plan_editor.dart';
import '../dates/date_plan_sheet.dart';
import '../discovery/profile_details_sheet.dart';
import '../safety/report_sheet.dart';
import 'chats_controller.dart';
import 'swipe_to_reply.dart';

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

  /// The other person typed recently: «печатает…» until this moment.
  DateTime? _typingUntil;
  Timer? _typingTimer;

  /// When I last told the server I am typing (at most every 3 s).
  DateTime _sentTypingAt = DateTime.fromMillisecondsSinceEpoch(0);

  /// A text that failed to send keeps its id, so a retry is stored once.
  String? _unsentText;
  String? _unsentId;

  /// The message the next one answers (chosen from a message's menu).
  ChatMessage? _replyTo;

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
    _typingTimer?.cancel();
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
      if (!message.mine) _setTyping(false);
      _add(message);
    } else if (event.name == 'typing' && event.data['matchId'] == widget.matchId) {
      _setTyping(true);
    }
  }

  /// The plan in play, or a new proposal (premium; the server answers 403 otherwise).
  Future<void> _openDatePlan() async {
    try {
      final plan = await _chats.api.getDatePlan(widget.matchId);
      if (!mounted) return;
      if (plan != null) {
        await showDatePlan(context, api: _chats.api, matchId: widget.matchId, planId: plan.id);
        return;
      }
      await Navigator.of(context).push<DatePlan>(MaterialPageRoute(
        builder: (_) => DatePlanEditorScreen(api: _chats.api, matchId: widget.matchId),
      ));
    } catch (e) {
      if (mounted) _snack(describeError(e));
    }
  }

  void _setTyping(bool on) {
    _typingTimer?.cancel();
    if (!mounted) return;
    setState(() => _typingUntil = on ? DateTime.now().add(const Duration(seconds: 5)) : null);
    if (on) {
      _typingTimer = Timer(const Duration(seconds: 5), () {
        if (mounted) setState(() => _typingUntil = null);
      });
    }
  }

  /// Best effort and throttled: a lost «typing» costs nothing.
  void _onInputChanged(String text) {
    final now = DateTime.now();
    if (text.trim().isEmpty || now.difference(_sentTypingAt) < const Duration(seconds: 3)) return;
    _sentTypingAt = now;
    _chats.api.sendTyping(widget.matchId).catchError((Object e) => debugPrint('typing: $e'));
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
        replyToMessageId: _replyTo?.id,
      );
      _unsentText = null;
      _unsentId = null;
      if (!mounted) return;
      setState(() => _replyTo = null);
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

  /// Long press on a message: reply to it, or report it (the other person's).
  Future<void> _messageMenu(ChatMessage message, Match? match) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.reply_rounded),
              title: const Text('Ответить'),
              onTap: () => Navigator.of(context).pop('reply'),
            ),
            if (!message.mine && match != null)
              ListTile(
                leading: const Icon(Icons.flag_outlined),
                title: const Text('Пожаловаться'),
                onTap: () => Navigator.of(context).pop('report'),
              ),
          ],
        ),
      ),
    );
    if (!mounted) return;
    switch (action) {
      case 'reply':
        setState(() => _replyTo = message);
      case 'report':
        await showReportSheet(
          context,
          api: _chats.api,
          profileId: match!.profile.id,
          name: match.profile.displayName,
          messageId: message.id,
        );
    }
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
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(match?.profile.displayName ?? 'Чат'),
            if (_typingUntil != null || match?.presenceText() != null)
              Text(
                _typingUntil != null ? 'печатает…' : match!.presenceText()!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: _typingUntil != null || match!.otherOnline
                      ? theme.colorScheme.primary
                      : theme.colorScheme.onSurfaceVariant,
                ),
              ),
          ],
        ),
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
                              if (message.isDatePlan) {
                                return _DatePlanCard(
                                  message: message,
                                  onOpen: () => showDatePlan(context,
                                      api: _chats.api, matchId: widget.matchId, planId: message.datePlanId),
                                );
                              }
                              return SwipeToReply(
                                onReply: () => setState(() => _replyTo = message),
                                child: _Bubble(
                                  message: message,
                                  read: message.mine &&
                                      readIndex >= 0 &&
                                      index >= readIndex,
                                  otherName: match?.profile.displayName ?? '',
                                  onMenu: () => _messageMenu(message, match),
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
          else ...[
            if (_replyTo case final reply?)
              Padding(
                padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.xs, 0),
                child: Row(
                  children: [
                    const Icon(Icons.reply_rounded, size: 20),
                    const SizedBox(width: Gaps.sm),
                    Expanded(
                      child: _Quote(
                        reply: reply.asReply,
                        otherName: match?.profile.displayName ?? '',
                        onBubble: false,
                      ),
                    ),
                    IconButton(
                      tooltip: 'Не отвечать',
                      onPressed: () => setState(() => _replyTo = null),
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ],
                ),
              ),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                    Gaps.md, Gaps.sm, Gaps.sm, Gaps.sm),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    IconButton(
                      tooltip: 'План встречи',
                      onPressed: _openDatePlan,
                      icon: const Icon(Icons.event_outlined),
                    ),
                    Expanded(
                      child: TextField(
                        controller: _input,
                        onChanged: _onInputChanged,
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
        ],
      ),
    );
  }
}

/// The answered message above a reply (in the bubble or over the composer).
class _Quote extends StatelessWidget {
  const _Quote({required this.reply, required this.otherName, required this.onBubble});

  final ReplyPreview reply;
  final String otherName;

  /// Inside my (rose) bubble the colours are white.
  final bool onBubble;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final accent = onBubble ? Colors.white : Brand.rose;
    final textColor = onBubble ? Colors.white70 : theme.colorScheme.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.only(left: Gaps.sm),
      decoration: BoxDecoration(border: Border(left: BorderSide(color: accent, width: 3))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(reply.mine ? 'Вы' : otherName,
              style: TextStyle(color: accent, fontWeight: FontWeight.w600, fontSize: 13)),
          Text(reply.preview,
              maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: textColor, fontSize: 13)),
        ],
      ),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.message, required this.read, required this.otherName, required this.onMenu});

  final ChatMessage message;
  final bool read;
  final String otherName;

  /// Long press: reply / report.
  final VoidCallback onMenu;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final mine = message.mine;
    final time = message.createdAt;
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: GestureDetector(
        onLongPress: onMenu,
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
              if (message.replyTo case final reply?)
                Padding(
                  padding: const EdgeInsets.only(bottom: Gaps.xs),
                  child: _Quote(reply: reply, otherName: otherName, onBubble: mine),
                ),
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

/// A date plan change in the chat: its summary and a way to open the plan.
class _DatePlanCard extends StatelessWidget {
  const _DatePlanCard({required this.message, required this.onOpen});

  final ChatMessage message;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final time = message.createdAt;
    return Align(
      alignment: message.mine ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.8),
        child: Card(
          margin: EdgeInsets.zero,
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onOpen,
            child: Padding(
              padding: const EdgeInsets.all(Gaps.md),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.event_rounded, color: Brand.rose, size: 20),
                      const SizedBox(width: Gaps.sm),
                      Text('План встречи', style: theme.textTheme.titleSmall),
                      const Spacer(),
                      if (time != null)
                        Text(formatDateTime(time).substring(11),
                            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                    ],
                  ),
                  const SizedBox(height: Gaps.xs),
                  Text(message.text ?? '', style: theme.textTheme.bodyMedium),
                  const SizedBox(height: Gaps.xs),
                  Text('Открыть', style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.primary)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
