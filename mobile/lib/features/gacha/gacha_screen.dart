import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/gacha.dart';
import '../../core/models/wallet.dart';
import '../cosmetics/cosmetic_visuals.dart';

const Map<String, String> _rarityNames = {
  'COMMON': 'Обычная',
  'RARE': 'Редкая',
  'EPIC': 'Эпическая',
  'LEGENDARY': 'Легендарная',
};

String _newKey() =>
    '${DateTime.now().microsecondsSinceEpoch}-${math.Random().nextInt(1 << 32)}';

/// Banners, my tickets and shards. Tap a banner to see its odds and pull.
class GachaScreen extends StatefulWidget {
  const GachaScreen({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<GachaScreen> createState() => _GachaScreenState();
}

class _GachaScreenState extends State<GachaScreen> {
  List<GachaBanner> _banners = const <GachaBanner>[];
  ShardsInfo? _shards;
  int _tickets = 0;
  bool _loading = true;
  bool _exchanging = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait<Object>([
        widget.api.getGachaBanners(),
        widget.api.getGachaShards(),
        widget.api.getWallet(),
      ]);
      if (!mounted) return;
      final wallet = results[2] as Wallet;
      setState(() {
        _banners = results[0] as List<GachaBanner>;
        _shards = results[1] as ShardsInfo;
        _tickets = _ticketsIn(wallet);
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = describeError(e);
      });
    }
  }

  static int _ticketsIn(Wallet wallet) {
    for (final item in wallet.items) {
      if (item.code == 'GACHA_TICKET') return item.quantity;
    }
    return 0;
  }

  Future<void> _exchange() async {
    final shards = _shards;
    if (shards == null || shards.affordableTickets == 0) return;
    setState(() => _exchanging = true);
    try {
      await widget.api.exchangeShards(shards.affordableTickets, _newKey());
      await _load();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text('Получено билетов: ${shards.affordableTickets}')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(describeError(e))));
      }
    } finally {
      if (mounted) setState(() => _exchanging = false);
    }
  }

  Future<void> _open(GachaBanner banner) async {
    await Navigator.of(context).push<void>(MaterialPageRoute<void>(
      builder: (_) => BannerScreen(api: widget.api, code: banner.code),
    ));
    if (mounted) _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Удача'),
        actions: [
          IconButton(
            tooltip: 'История',
            icon: const Icon(Icons.history),
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute<void>(
                  builder: (_) => GachaHistoryScreen(api: widget.api)),
            ),
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: TextButton(
          onPressed: _load,
          child: Text('${_error!}\nПовторить', textAlign: TextAlign.center),
        ),
      );
    }
    final shards = _shards;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          Card(
            child: ListTile(
              leading: const Icon(Icons.confirmation_number_outlined),
              title: Text('Билеты: $_tickets'),
              subtitle: shards == null
                  ? null
                  : Text('Осколки: ${shards.balance} '
                      '(${shards.shardsPerTicket} = 1 билет)'),
              trailing: shards != null && shards.affordableTickets > 0
                  ? FilledButton.tonal(
                      onPressed: _exchanging ? null : _exchange,
                      child: Text('Обменять на ${shards.affordableTickets}'),
                    )
                  : null,
            ),
          ),
          if (_banners.isEmpty)
            const Padding(
              padding: EdgeInsets.all(32),
              child: Center(child: Text('Сейчас нет открытых баннеров')),
            ),
          for (final banner in _banners)
            _BannerCard(banner: banner, onTap: () => _open(banner)),
        ],
      ),
    );
  }
}

class _BannerCard extends StatelessWidget {
  const _BannerCard({required this.banner, required this.onTap});

  final GachaBanner banner;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final endsAt = banner.endsAt;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(banner.title, style: theme.textTheme.titleLarge),
                  ),
                  if (banner.limited)
                    const Chip(
                      label: Text('Лимитный'),
                      visualDensity: VisualDensity.compact,
                    ),
                ],
              ),
              if ((banner.description ?? '').isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(banner.description!),
                ),
              if (endsAt != null)
                Text('До ${formatDateTime(endsAt)}',
                    style: theme.textTheme.bodySmall),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final entry in banner.featured.take(6))
                    _RewardChip(rarity: entry.rarity, reward: entry.reward),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '1 крутка — ${banner.costPerPull} билет, '
                '${banner.multiPullSize} круток — ${banner.multiPullCost}',
                style: theme.textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RewardChip extends StatelessWidget {
  const _RewardChip({required this.rarity, required this.reward});

  final String rarity;
  final GachaReward reward;

  @override
  Widget build(BuildContext context) {
    final color = rarityColor(rarity) ?? Colors.grey;
    final item = reward.item;
    return Container(
      padding: const EdgeInsets.all(6),
      decoration: BoxDecoration(
        border: Border.all(color: color, width: 2),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox.square(
            dimension: 32,
            child: FittedBox(
              child: item != null
                  ? ItemPreview(item: item)
                  : const Icon(Icons.workspace_premium, color: Colors.amber),
            ),
          ),
          const SizedBox(width: 6),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 140),
            child: Text(reward.label,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 12)),
          ),
        ],
      ),
    );
  }
}

/// One banner: odds, pool, guarantees and the pull buttons.
class BannerScreen extends StatefulWidget {
  const BannerScreen({super.key, required this.api, required this.code});

  final DatingApiClient api;
  final String code;

  @override
  State<BannerScreen> createState() => _BannerScreenState();
}

class _BannerScreenState extends State<BannerScreen> {
  GachaBanner? _banner;
  PityProgress? _pity;
  String? _error;
  bool _pulling = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final banner = await widget.api.getGachaBanner(widget.code);
      if (!mounted) return;
      setState(() {
        _banner = banner;
        _pity = banner.pity;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e));
    }
  }

  Future<void> _pull(int count) async {
    final banner = _banner;
    if (banner == null || _pulling) return;
    setState(() => _pulling = true);
    try {
      final response = await widget.api.pullGacha(banner.code, count, _newKey());
      if (!mounted) return;
      setState(() => _pity = response.pity);
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        builder: (_) => _ResultsSheet(response: response),
      );
    } catch (e) {
      if (!mounted) return;
      final message = e is ApiException && e.code == 'INSUFFICIENT_BALANCE'
          ? 'Не хватает билетов. Их дают за достижения, премиум и обмен осколков'
          : describeError(e);
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(message)));
    } finally {
      if (mounted) setState(() => _pulling = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final banner = _banner;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(banner?.title ?? 'Баннер')),
      body: banner == null
          ? Center(
              child: _error == null
                  ? const CircularProgressIndicator()
                  : TextButton(
                      onPressed: _load,
                      child: Text('${_error!}\nПовторить',
                          textAlign: TextAlign.center),
                    ),
            )
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if ((banner.description ?? '').isNotEmpty)
                  Text(banner.description!),
                const SizedBox(height: 12),
                _PityCard(pity: _pity ?? banner.pity),
                const SizedBox(height: 12),
                Text('Шансы за одну крутку', style: theme.textTheme.titleMedium),
                for (final rarity in banner.rarityPercents.entries)
                  ListTile(
                    dense: true,
                    leading: CircleAvatar(
                      radius: 8,
                      backgroundColor: rarityColor(rarity.key),
                    ),
                    title: Text(_rarityNames[rarity.key] ?? rarity.key),
                    trailing: Text('${rarity.value.toStringAsFixed(2)} %'),
                  ),
                const Divider(),
                Text('Что можно получить', style: theme.textTheme.titleMedium),
                const SizedBox(height: 8),
                for (final entry in banner.pool)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: SizedBox.square(
                      dimension: 40,
                      child: FittedBox(
                        child: entry.reward.item != null
                            ? ItemPreview(item: entry.reward.item!)
                            : const Icon(Icons.workspace_premium,
                                color: Colors.amber),
                      ),
                    ),
                    title: Text(entry.reward.label),
                    subtitle: Text(_rarityNames[entry.rarity] ?? entry.rarity,
                        style: TextStyle(color: rarityColor(entry.rarity))),
                    trailing: Text('${entry.percent.toStringAsFixed(2)} %'),
                  ),
                const SizedBox(height: 80),
              ],
            ),
      bottomNavigationBar: banner == null || !banner.open
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _pulling ? null : () => _pull(1),
                        child: Text('×1 (${banner.costPerPull} 🎟)'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: FilledButton(
                        onPressed: _pulling
                            ? null
                            : () => _pull(banner.multiPullSize),
                        child: Text(
                            '×${banner.multiPullSize} (${banner.multiPullCost} 🎟)'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}

class _PityCard extends StatelessWidget {
  const _PityCard({required this.pity});

  final PityProgress pity;

  @override
  Widget build(BuildContext context) {
    final epic = pity.epicGuaranteedIn;
    final legendary = pity.legendaryGuaranteedIn;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (epic != null)
              Text(epic == 1
                  ? 'Следующая крутка — гарантированно эпическая или лучше'
                  : 'Эпическая или лучше — не позже чем через $epic круток'),
            if (legendary != null)
              Text(legendary == 1
                  ? 'Следующая крутка — гарантированно легендарная'
                  : 'Легендарная — не позже чем через $legendary круток'),
            Text('Всего круток: ${pity.totalPulls}',
                style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    );
  }
}

class _ResultsSheet extends StatelessWidget {
  const _ResultsSheet({required this.response});

  final PullResponse response;

  @override
  Widget build(BuildContext context) {
    final results = [...response.results]
      ..sort((a, b) => _rank(b.rarity).compareTo(_rank(a.rarity)));
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Выпало', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final r in results)
                    Card(
                      shape: RoundedRectangleBorder(
                        side: BorderSide(
                            color: rarityColor(r.rarity) ?? Colors.grey,
                            width: 2),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: ListTile(
                        leading: SizedBox.square(
                          dimension: 40,
                          child: FittedBox(
                            child: r.reward.item != null
                                ? ItemPreview(item: r.reward.item!)
                                : const Icon(Icons.workspace_premium,
                                    color: Colors.amber),
                          ),
                        ),
                        title: Text(r.reward.label),
                        subtitle: Text([
                          _rarityNames[r.rarity] ?? r.rarity,
                          if (r.duplicate) 'уже есть: +${r.shardsGranted} осколков',
                          if (r.pityTrigger == 'EPIC_PITY' ||
                              r.pityTrigger == 'LEGENDARY_PITY')
                            'гарантия',
                        ].join(' · ')),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Text('Осталось билетов: ${response.ticketsLeft} · '
                'осколков: ${response.shards}'),
            const SizedBox(height: 8),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Забрать'),
            ),
          ],
        ),
      ),
    );
  }

  static int _rank(String rarity) =>
      const ['COMMON', 'RARE', 'EPIC', 'LEGENDARY'].indexOf(rarity);
}

/// My pulls, newest first.
class GachaHistoryScreen extends StatefulWidget {
  const GachaHistoryScreen({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<GachaHistoryScreen> createState() => _GachaHistoryScreenState();
}

class _GachaHistoryScreenState extends State<GachaHistoryScreen> {
  final List<GachaHistoryItem> _items = <GachaHistoryItem>[];
  int? _next;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _more();
  }

  Future<void> _more() async {
    setState(() => _loading = true);
    try {
      final page = await widget.api.getGachaHistory(before: _next);
      if (!mounted) return;
      setState(() {
        _items.addAll(page.items);
        _next = page.nextBefore;
        _error = null;
      });
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('История круток')),
      body: _items.isEmpty && !_loading
          ? Center(child: Text(_error ?? 'Пока ни одной крутки'))
          : ListView.builder(
              itemCount: _items.length + 1,
              itemBuilder: (context, index) {
                if (index == _items.length) {
                  if (_loading) {
                    return const Padding(
                      padding: EdgeInsets.all(16),
                      child: Center(child: CircularProgressIndicator()),
                    );
                  }
                  return _next == null
                      ? const SizedBox(height: 24)
                      : TextButton(
                          onPressed: _more, child: const Text('Показать ещё'));
                }
                final item = _items[index];
                final created = item.createdAt;
                return ListTile(
                  leading: CircleAvatar(
                    radius: 8,
                    backgroundColor: rarityColor(item.rarity),
                  ),
                  title: Text(item.reward.label),
                  subtitle: Text([
                    item.bannerTitle,
                    if (item.duplicate) '+${item.shardsGranted} осколков',
                    if (created != null) formatDateTime(created),
                  ].join(' · ')),
                );
              },
            ),
    );
  }
}
