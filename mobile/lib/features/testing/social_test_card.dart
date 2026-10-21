import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/social.dart';

/// Test panel block for likes and chats: seed profiles like me, match with me
/// and write to me; my reactions can be reset to swipe the same people again.
class SocialTestCard extends StatefulWidget {
  const SocialTestCard({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<SocialTestCard> createState() => _SocialTestCardState();
}

class _SocialTestCardState extends State<SocialTestCard> {
  final TextEditingController _text =
      TextEditingController(text: 'Привет! Как дела?');
  List<SeedProfileRef> _seeds = const <SeedProfileRef>[];
  String? _seedId;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _loadSeeds();
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _loadSeeds() async {
    try {
      final seeds = (await widget.api.getSeedProfiles())
          .where((s) => s.published)
          .toList();
      if (!mounted) return;
      setState(() {
        _seeds = seeds;
        _seedId = seeds.isEmpty ? null : (_seedId ?? seeds.first.accountId);
      });
    } catch (e) {
      if (mounted) _snack(describeError(e));
    }
  }

  Future<void> _run(Future<String> Function() action) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final done = await action();
      if (mounted) _snack(done);
    } catch (e) {
      if (mounted) {
        _snack(e is ApiException ? '${e.message}\n${e.code}' : describeError(e));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _snack(String text) => ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(text)));

  String get _seedName =>
      _seeds.firstWhere((s) => s.accountId == _seedId).name;

  @override
  Widget build(BuildContext context) {
    final api = widget.api;
    final seedId = _seedId;
    final hasSeed = seedId != null && !_busy;
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Лайки и чаты', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton(
                  onPressed: _busy
                      ? null
                      : () => _run(() async =>
                          'Сброшено реакций: ${await api.clearTestReactions()}. '
                          'Обновите ленту'),
                  child: const Text('Сбросить мои лайки и пропуски'),
                ),
                OutlinedButton(
                  onPressed: _busy
                      ? null
                      : () => _run(() async =>
                          'Вернулось анкет: ${await api.clearTestPasses()}. '
                          'Обновите ленту'),
                  child: const Text('Вернуть пропущенных'),
                ),
              ],
            ),
            const Divider(height: 24),
            if (_seeds.isEmpty)
              const Text('Нет опубликованных тестовых анкет '
                  '(загрузите их скриптом scripts/seed-profiles.mjs)')
            else ...[
              DropdownButton<String>(
                isExpanded: true,
                value: seedId,
                items: [
                  for (final seed in _seeds)
                    DropdownMenuItem(value: seed.accountId, child: Text(seed.name)),
                ],
                onChanged: (id) => setState(() => _seedId = id),
              ),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.tonal(
                    onPressed: !hasSeed
                        ? null
                        : () => _run(() async {
                              await api.seedLikesMe(seedId);
                              return '$_seedName лайкнул(а) вас';
                            }),
                    child: const Text('Лайкнул меня'),
                  ),
                  FilledButton.tonal(
                    onPressed: !hasSeed
                        ? null
                        : () => _run(() async {
                              await api.seedLikesMe(seedId, superlike: true);
                              return '$_seedName поставил(а) суперлайк';
                            }),
                    child: const Text('Суперлайк мне'),
                  ),
                  FilledButton.tonal(
                    onPressed: !hasSeed
                        ? null
                        : () => _run(() async {
                              await api.seedMatch(seedId);
                              return 'Мэтч с $_seedName — смотрите «Чаты»';
                            }),
                    child: const Text('Мэтч'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _text,
                decoration: const InputDecoration(
                  labelText: 'Сообщение от анкеты',
                  isDense: true,
                ),
              ),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: !hasSeed
                    ? null
                    : () => _run(() async {
                          final text = _text.text.trim();
                          if (text.isEmpty) return 'Введите текст';
                          // Creates the match if needed (a new one if it was closed).
                          final matchId = await api.seedMatch(seedId);
                          await api.seedWrites(matchId, text);
                          return '$_seedName написал(а) вам';
                        }),
                child: const Text('Написать мне от этой анкеты'),
              ),
              const Divider(height: 24),
              OutlinedButton(
                onPressed: _busy
                    ? null
                    : () => _run(() async {
                          for (final seed in _seeds) {
                            await api.seedLikesMe(seed.accountId);
                          }
                          return 'Вас лайкнули ${_seeds.length} анкет';
                        }),
                child: Text('Все ${_seeds.length} анкет лайкнули меня'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
