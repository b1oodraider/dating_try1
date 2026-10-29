import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/community.dart';
import '../../core/theme/app_theme.dart';

/// One group: what it is, «Подписаться» and «Начать поиск» (the feed among its members).
class CommunityScreen extends StatefulWidget {
  const CommunityScreen({super.key, required this.api, required this.community, required this.onSearch});

  final DatingApiClient api;
  final Community community;
  final void Function(Community community) onSearch;

  @override
  State<CommunityScreen> createState() => _CommunityScreenState();
}

class _CommunityScreenState extends State<CommunityScreen> {
  late Community _community = widget.community;
  bool _busy = false;

  Future<void> _toggle() async {
    setState(() => _busy = true);
    try {
      final updated = await widget.api.setCommunitySubscription(_community.id, subscribed: !_community.subscribed);
      if (mounted) setState(() => _community = updated);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(describeError(e))));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _search() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    widget.onSearch(_community);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final c = _community;
    return Scaffold(
      appBar: AppBar(),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(Gaps.lg, 0, Gaps.lg, Gaps.lg),
        children: [
          Text(c.emoji ?? '✨', style: const TextStyle(fontSize: 56)),
          const SizedBox(height: Gaps.sm),
          Text(c.title, style: theme.textTheme.headlineMedium),
          const SizedBox(height: Gaps.xs),
          Text('${c.kindLabel} · ${membersText(c.members)}',
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          if (c.description != null) ...[
            const SizedBox(height: Gaps.md),
            Text(c.description!, style: theme.textTheme.bodyLarge),
          ],
          const SizedBox(height: Gaps.lg),
          Text(
            c.subscribed
                ? 'Группа видна в вашей анкете, а в фильтрах ленты можно искать среди её участников.'
                : 'Подпишитесь — группа появится в анкете, и люди из неё увидят, что у вас есть общее.',
            style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(Gaps.lg, Gaps.sm, Gaps.lg, Gaps.md),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (c.subscribed)
                OutlinedButton.icon(
                  onPressed: _busy ? null : _toggle,
                  icon: const Icon(Icons.check_rounded),
                  label: const Text('Вы подписаны'),
                )
              else
                FilledButton(onPressed: _busy ? null : _toggle, child: const Text('Подписаться')),
              const SizedBox(height: Gaps.sm),
              FilledButton.tonalIcon(
                onPressed: _search,
                icon: const Icon(Icons.travel_explore_rounded),
                label: const Text('Начать поиск'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
