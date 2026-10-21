import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/support.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/empty_state.dart';

/// Shown until GET /faq answers (offline, old server); the live list is edited in the staff cabinet.
const List<(String, String)> _builtInFaq = [
  (
    'Куда пропадают лайки?',
    'В «Симпатиях» остаются только те, кому вы ещё не ответили: после ответа человек уходит из списка (при взаимности — '
        'в «Чаты»). Лайк может исчезнуть, если человек скрыл анкету, заблокировал вас, удалил аккаунт или не заходил '
        'больше 30 дней. Пока ваше селфи не подтверждено, ваши лайки ждут проверки и не видны другим (до 24 часов, '
        'время на проверке не считается).'
  ),
  (
    'Почему мою анкету не показывают в ленте?',
    'Анкета должна быть опубликована и с подтверждённым селфи. Её скрывают, если вы не заходили 30 дней или есть '
        'ограничение от модератора — оно видно наверху вашего профиля.'
  ),
  (
    'Как подтвердить селфи и зачем?',
    'Профиль → «Проверка селфи»: сделайте селфи с жестом из подсказки, его сравнит модератор. После этого у анкеты '
        'появится значок «Проверен», анкету начнут показывать другим, а ваши лайки уйдут адресатам.'
  ),
  (
    'Фото не загружается или «на проверке»',
    'На фото должен быть человек. Пока селфи не подтверждено, на каждом фото должно быть видно лицо. Если лицо на '
        'новом фото не совпало с вашими подтверждёнными фото, его посмотрит модератор — до этого другие его не видят.'
  ),
  (
    'Как вернуть пропущенные анкеты?',
    'Когда анкеты в ленте закончатся, нажмите «Показать пропущенных снова» — бесплатно раз в сутки. С премиумом — '
        'в любой момент, а последнюю анкету можно вернуть кнопкой ↺ под карточкой.'
  ),
  (
    'Как изменить пол или дату рождения?',
    'После публикации — только через поддержку: напишите обращение в теме «Анкета и фото».'
  ),
  (
    'Пропал чат с человеком',
    'Человек мог отменить пару или заблокировать вас — тогда чат закрывается у обоих.'
  ),
  (
    'Что даёт премиум?',
    'Список тех, кто вас лайкнул, расширенные фильтры (рост, слова-исключения), возврат анкет, невидимку и '
        'регулярные суперлайки и бусты.'
  ),
];

/// Help: FAQ, my tickets and a new ticket.
class SupportScreen extends StatefulWidget {
  const SupportScreen({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<SupportScreen> createState() => _SupportScreenState();
}

class _SupportScreenState extends State<SupportScreen> {
  List<SupportTicket>? _tickets;
  String? _error;
  List<(String, String)> _faq = _builtInFaq;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    widget.api.getFaq().then((faq) {
      if (mounted && faq.isNotEmpty) setState(() => _faq = faq);
    }, onError: (Object e) => debugPrint('Failed to load FAQ: $e'));
    try {
      final tickets = await widget.api.getSupportTickets();
      if (!mounted) return;
      setState(() {
        _tickets = tickets;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e));
    }
  }

  Future<void> _open(Widget screen) async {
    await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => screen));
    if (mounted) _load();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tickets = _tickets;
    return Scaffold(
      appBar: AppBar(title: const Text('Помощь')),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.md),
          child: FilledButton.icon(
            onPressed: () => _open(NewTicketScreen(api: widget.api)),
            icon: const Icon(Icons.edit_outlined),
            label: const Text('Написать в поддержку'),
          ),
        ),
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.lg),
          children: [
            if (tickets != null && tickets.isNotEmpty) ...[
              Text('Мои обращения', style: theme.textTheme.titleMedium),
              const SizedBox(height: Gaps.sm),
              Card(
                clipBehavior: Clip.antiAlias,
                child: Column(
                  children: [
                    for (final t in tickets)
                      ListTile(
                        title: Text(t.categoryLabel),
                        subtitle: Text(
                            [t.statusLabel, if (t.lastMessageAt != null) formatDateTime(t.lastMessageAt!)].join(' · ')),
                        trailing: t.status == 'ANSWERED'
                            ? const Badge(label: Text('ответ'), backgroundColor: Brand.like)
                            : const Icon(Icons.chevron_right_rounded),
                        onTap: () => _open(TicketScreen(api: widget.api, ticketId: t.id)),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: Gaps.lg),
            ],
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: Gaps.md),
                child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
              ),
            Text('Частые вопросы', style: theme.textTheme.titleMedium),
            const SizedBox(height: Gaps.sm),
            Card(
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  for (final (q, a) in _faq)
                    ExpansionTile(
                      shape: const Border(),
                      title: Text(q, style: theme.textTheme.titleSmall),
                      childrenPadding: const EdgeInsets.fromLTRB(Gaps.md, 0, Gaps.md, Gaps.md),
                      expandedAlignment: Alignment.centerLeft,
                      children: [Text(a, style: theme.textTheme.bodyMedium)],
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class NewTicketScreen extends StatefulWidget {
  const NewTicketScreen({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<NewTicketScreen> createState() => _NewTicketScreenState();
}

class _NewTicketScreenState extends State<NewTicketScreen> {
  final TextEditingController _text = TextEditingController();
  String _category = 'OTHER';
  bool _sending = false;
  String? _error;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final text = _text.text.trim();
    if (text.isEmpty) {
      setState(() => _error = 'Опишите, что случилось');
      return;
    }
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      final ticket = await widget.api.createSupportTicket(_category, text);
      if (!mounted) return;
      Navigator.of(context).pushReplacement(MaterialPageRoute<void>(
          builder: (_) => TicketScreen(api: widget.api, ticketId: ticket.id)));
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e, field: 'text'));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Новое обращение')),
        body: ListView(
          padding: const EdgeInsets.all(Gaps.md),
          children: [
            DropdownButtonFormField<String>(
              initialValue: _category,
              decoration: const InputDecoration(labelText: 'Тема'),
              items: [
                for (final e in supportCategories.entries) DropdownMenuItem(value: e.key, child: Text(e.value)),
              ],
              onChanged: (v) => setState(() => _category = v ?? 'OTHER'),
            ),
            const SizedBox(height: Gaps.md),
            TextField(
              controller: _text,
              minLines: 5,
              maxLines: 12,
              maxLength: 4000,
              textCapitalization: TextCapitalization.sentences,
              decoration: InputDecoration(
                labelText: 'Что случилось?',
                hintText: 'Опишите проблему; если это ошибка — что вы делали перед ней',
                alignLabelWithHint: true,
                errorText: _error,
              ),
            ),
            const SizedBox(height: Gaps.md),
            FilledButton(
              onPressed: _sending ? null : _send,
              child: Text(_sending ? 'Отправляем…' : 'Отправить'),
            ),
          ],
        ),
      );
}

/// One ticket as a chat with support.
class TicketScreen extends StatefulWidget {
  const TicketScreen({super.key, required this.api, required this.ticketId});

  final DatingApiClient api;
  final String ticketId;

  @override
  State<TicketScreen> createState() => _TicketScreenState();
}

class _TicketScreenState extends State<TicketScreen> {
  final TextEditingController _input = TextEditingController();
  SupportTicket? _ticket;
  String? _error;
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final t = await widget.api.getSupportTicket(widget.ticketId);
      if (!mounted) return;
      setState(() {
        _ticket = t;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e));
    }
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    setState(() => _sending = true);
    try {
      final t = await widget.api.writeToSupport(widget.ticketId, text);
      if (!mounted) return;
      _input.clear();
      setState(() => _ticket = t);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(describeError(e))));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final t = _ticket;
    return Scaffold(
      appBar: AppBar(title: Text(t?.categoryLabel ?? 'Обращение')),
      body: t == null
          ? (_error == null
              ? const Center(child: CircularProgressIndicator())
              : EmptyState(
                  icon: Icons.wifi_off_rounded,
                  title: 'Не удалось загрузить',
                  message: _error,
                  actions: [FilledButton(onPressed: _load, child: const Text('Повторить'))]))
          : Column(
              children: [
                Expanded(
                  child: RefreshIndicator(
                    onRefresh: _load,
                    child: ListView(
                      padding: const EdgeInsets.all(Gaps.md),
                      children: [
                        Center(
                          child: Text(t.statusLabel,
                              style: theme.textTheme.labelMedium
                                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                        ),
                        const SizedBox(height: Gaps.sm),
                        for (final m in t.messages)
                          Align(
                            alignment: m.fromStaff ? Alignment.centerLeft : Alignment.centerRight,
                            child: Container(
                              margin: const EdgeInsets.symmetric(vertical: 4),
                              padding: const EdgeInsets.fromLTRB(14, 9, 12, 7),
                              constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.78),
                              decoration: BoxDecoration(
                                color: m.fromStaff ? theme.colorScheme.surfaceContainerLow : Brand.rose,
                                borderRadius: BorderRadius.circular(18),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  if (m.fromStaff)
                                    Text('Поддержка',
                                        style: theme.textTheme.labelSmall
                                            ?.copyWith(color: theme.colorScheme.primary, fontWeight: FontWeight.w800)),
                                  Text(m.text,
                                      style: TextStyle(
                                          color: m.fromStaff ? theme.colorScheme.onSurface : Colors.white,
                                          height: 1.35)),
                                  if (m.createdAt != null)
                                    Text(formatDateTime(m.createdAt!),
                                        style: TextStyle(
                                            fontSize: 10,
                                            color: m.fromStaff
                                                ? theme.colorScheme.onSurfaceVariant
                                                : Colors.white70)),
                                ],
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
                SafeArea(
                  top: false,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.sm, Gaps.sm),
                    child: Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _input,
                            minLines: 1,
                            maxLines: 4,
                            maxLength: 4000,
                            textCapitalization: TextCapitalization.sentences,
                            decoration: const InputDecoration(hintText: 'Сообщение', counterText: '', isDense: true),
                          ),
                        ),
                        IconButton(
                          tooltip: 'Отправить',
                          onPressed: _sending ? null : _send,
                          icon: Icon(Icons.send_rounded, color: theme.colorScheme.primary),
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
