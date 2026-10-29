import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/community.dart';
import '../../core/theme/app_theme.dart';
import 'community_screen.dart';

/// «Интересы»: interest groups — search, kinds, my groups; a group opens its page.
class CommunitiesScreen extends StatefulWidget {
  const CommunitiesScreen({super.key, required this.api, required this.onSearch});

  final DatingApiClient api;

  /// «Начать поиск» on a group: the feed among its members.
  final void Function(Community community) onSearch;

  @override
  State<CommunitiesScreen> createState() => _CommunitiesScreenState();
}

class _CommunitiesScreenState extends State<CommunitiesScreen> {
  final TextEditingController _query = TextEditingController();
  Timer? _debounce;
  String? _kind;
  List<Community> _items = const <Community>[];
  List<Community> _mine = const <Community>[];
  bool _loading = true;
  String? _error;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final generation = ++_generation;
    try {
      final results = await Future.wait([
        widget.api.getCommunities(query: _query.text, kind: _kind),
        widget.api.getMyCommunities(),
      ]);
      if (!mounted || generation != _generation) return;
      setState(() {
        _items = results[0];
        _mine = results[1];
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _loading = false;
        _error = describeError(e);
      });
    }
  }

  void _onQueryChanged(String _) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 350), _load);
  }

  Future<void> _open(Community community) async {
    await Navigator.of(context).push<void>(MaterialPageRoute<void>(
      builder: (_) => CommunityScreen(api: widget.api, community: community, onSearch: widget.onSearch),
    ));
    if (mounted) _load();
  }

  Future<void> _suggest() async {
    final sent = await showDialog<bool>(context: context, builder: (_) => _SuggestDialog(api: widget.api));
    if (sent == true && mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Спасибо! Мы посмотрим и, возможно, добавим группу')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final browsing = _query.text.trim().isEmpty && _kind == null;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Интересы'),
        actions: [
          IconButton(
            tooltip: 'Предложить группу',
            icon: const Icon(Icons.add_circle_outline_rounded),
            onPressed: _suggest,
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(
          padding: const EdgeInsets.only(bottom: Gaps.xl),
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(Gaps.md, 0, Gaps.md, Gaps.sm),
              child: TextField(
                controller: _query,
                onChanged: _onQueryChanged,
                textInputAction: TextInputAction.search,
                decoration: const InputDecoration(
                  hintText: 'Фильм, книга, сериал, игра…',
                  prefixIcon: Icon(Icons.search_rounded),
                ),
              ),
            ),
            SizedBox(
              height: 48,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: Gaps.md),
                children: [
                  for (final (code, label) in [(null, 'Все'), ...communityKinds.entries.map((e) => (e.key, e.value))])
                    Padding(
                      padding: const EdgeInsets.only(right: Gaps.sm),
                      child: ChoiceChip(
                        chipAnimationStyle: instantChip,
                        label: Text(label),
                        selected: _kind == code,
                        onSelected: (_) {
                          setState(() => _kind = code);
                          _load();
                        },
                      ),
                    ),
                ],
              ),
            ),
            if (browsing && _mine.isNotEmpty) ...[
              Padding(
                padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.md, Gaps.md, Gaps.sm),
                child: Text('Мои группы', style: theme.textTheme.titleMedium),
              ),
              SizedBox(
                height: 44,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: Gaps.md),
                  children: [
                    for (final c in _mine)
                      Padding(
                        padding: const EdgeInsets.only(right: Gaps.sm),
                        child: ActionChip(label: Text(c.display), onPressed: () => _open(c)),
                      ),
                  ],
                ),
              ),
            ],
            Padding(
              padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.md, Gaps.md, 0),
              child: Text(browsing ? 'Популярные' : 'Найдено', style: theme.textTheme.titleMedium),
            ),
            if (_loading)
              const Padding(padding: EdgeInsets.all(Gaps.xl), child: Center(child: CircularProgressIndicator()))
            else if (_error != null)
              Padding(
                padding: const EdgeInsets.all(Gaps.md),
                child: TextButton(onPressed: _load, child: Text('$_error\nПовторить', textAlign: TextAlign.center)),
              )
            else if (_items.isEmpty)
              Padding(
                padding: const EdgeInsets.all(Gaps.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('Такой группы пока нет.'),
                    TextButton.icon(
                      onPressed: _suggest,
                      icon: const Icon(Icons.add_rounded),
                      label: const Text('Предложить группу'),
                    ),
                  ],
                ),
              )
            else
              for (final c in _items)
                ListTile(
                  leading: _Emoji(c.emoji),
                  title: Text(c.title),
                  subtitle: Text('${c.kindLabel} · ${membersText(c.members)}'),
                  trailing: c.subscribed
                      ? Icon(Icons.check_circle_rounded, color: theme.colorScheme.primary, semanticLabel: 'Вы подписаны')
                      : const Icon(Icons.chevron_right_rounded),
                  onTap: () => _open(c),
                ),
          ],
        ),
      ),
    );
  }
}

class _Emoji extends StatelessWidget {
  const _Emoji(this.emoji);

  final String? emoji;

  @override
  Widget build(BuildContext context) => CircleAvatar(
        backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
        child: Text(emoji ?? '✨', style: const TextStyle(fontSize: 20)),
      );
}

/// «Предложить группу»: a title and why; staff decide.
class _SuggestDialog extends StatefulWidget {
  const _SuggestDialog({required this.api});

  final DatingApiClient api;

  @override
  State<_SuggestDialog> createState() => _SuggestDialogState();
}

class _SuggestDialogState extends State<_SuggestDialog> {
  final TextEditingController _title = TextEditingController();
  final TextEditingController _comment = TextEditingController();
  bool _sending = false;
  String? _error;

  @override
  void dispose() {
    _title.dispose();
    _comment.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (_title.text.trim().length < 2) {
      setState(() => _error = 'Напишите название');
      return;
    }
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await widget.api.suggestCommunity(_title.text.trim(), comment: _comment.text);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _sending = false;
          _error = describeError(e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('Предложить группу'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _title,
              autofocus: true,
              maxLength: 80,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Название', hintText: 'Например: Сейлор Мун'),
            ),
            TextField(
              controller: _comment,
              maxLength: 300,
              minLines: 1,
              maxLines: 3,
              textCapitalization: TextCapitalization.sentences,
              decoration: const InputDecoration(labelText: 'Что это — по желанию'),
            ),
            if (_error != null)
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
        ),
        actions: [
          TextButton(onPressed: _sending ? null : () => Navigator.of(context).pop(false), child: const Text('Отмена')),
          FilledButton(onPressed: _sending ? null : _send, child: const Text('Отправить')),
        ],
      );
}
