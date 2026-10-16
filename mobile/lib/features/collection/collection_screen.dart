import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/json_utils.dart';
import '../../core/models/wallet.dart';
import '../auth/session_controller.dart';
import '../cosmetics/cosmetic_visuals.dart';
import '../premium/premium_badge.dart';

/// "Коллекция": premium status, consumables and owned cosmetics
/// (`GET /me/wallet`); equippable cosmetics can be put on / taken off.
class CollectionScreen extends StatefulWidget {
  const CollectionScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<CollectionScreen> createState() => _CollectionScreenState();
}

class _CollectionScreenState extends State<CollectionScreen> {
  Wallet? _wallet;
  bool _loading = true;
  String? _loadError;

  /// Slot whose equip / unequip request is in flight.
  String? _busySlot;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final wallet = await widget.session.api.getWallet();
      if (!mounted) return;
      setState(() {
        _wallet = wallet;
        _loading = false;
        _loadError = null;
      });
    } catch (e) {
      if (!mounted) return;
      // On 401 the SessionController already switches to the login screen.
      if (e is ApiException && e.isUnauthorized) return;
      final message = describeError(e);
      if (_wallet != null) {
        // Keep showing the old wallet, just report the failed refresh.
        _showMessage(message);
      } else {
        setState(() {
          _loading = false;
          _loadError = message;
        });
      }
    }
  }

  void _retryLoad() {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    _load();
  }

  Future<void> _toggleEquipped(WalletItem item) async {
    final wallet = _wallet;
    final slot = item.equipSlot;
    if (wallet == null || slot == null || _busySlot != null) return;

    final equip = !wallet.isEquipped(item);
    setState(() => _busySlot = slot);
    try {
      final api = widget.session.api;
      final updated = equip
          ? await api.equipCosmetic(slot, item.code)
          : await api.unequipCosmetic(slot);
      if (!mounted) return;
      setState(() {
        _wallet = updated;
        _busySlot = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _busySlot = null);
      if (e is ApiException && e.isUnauthorized) return;
      _showMessage(describeError(e));
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Коллекция')),
      body: SafeArea(child: _buildBody(context)),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }

    final loadError = _loadError;
    final wallet = _wallet;
    if (loadError != null || wallet == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 48),
              const SizedBox(height: 12),
              Text(
                loadError ?? 'Не удалось загрузить коллекцию',
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              FilledButton.tonal(
                onPressed: _retryLoad,
                child: const Text('Повторить'),
              ),
            ],
          ),
        ),
      );
    }

    final consumables = wallet.consumables;
    final groups = _groupByCategory(wallet.cosmetics);

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          _PremiumCard(wallet: wallet),
          const _SectionHeader('Расходники'),
          if (consumables.isEmpty)
            const _EmptyHint('Пока нет')
          else
            for (final item in consumables) _ConsumableTile(item: item),
          const _SectionHeader('Косметика'),
          if (groups.isEmpty) const _EmptyHint('Косметики пока нет'),
          for (final entry in groups.entries) ...[
            _SubsectionHeader(categoryLabel(entry.key)),
            for (final item in entry.value)
              _CosmeticTile(
                item: item,
                equipped: wallet.isEquipped(item),
                busy: _busySlot != null && _busySlot == item.equipSlot,
                enabled: _busySlot == null,
                onToggle: () => _toggleEquipped(item),
              ),
          ],
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  /// Known categories in a fixed order, then unknown ones as they come.
  static Map<String, List<WalletItem>> _groupByCategory(
    List<WalletItem> items,
  ) {
    final byCategory = <String, List<WalletItem>>{};
    for (final item in items) {
      byCategory.putIfAbsent(item.category, () => <WalletItem>[]).add(item);
    }
    final ordered = <String, List<WalletItem>>{};
    for (final category in cosmeticCategoryOrder) {
      final group = byCategory.remove(category);
      if (group != null) ordered[category] = group;
    }
    ordered.addAll(byCategory);
    return ordered;
  }
}

class _PremiumCard extends StatelessWidget {
  const _PremiumCard({required this.wallet});

  final Wallet wallet;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final premium = wallet.premium;
    final until = premium.until;
    final String text;
    if (!premium.active) {
      text = 'Премиум не активен';
    } else if (until != null) {
      text = 'Премиум активен до ${formatDateTime(until)}';
    } else {
      text = 'Премиум активен';
    }

    return Card(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      color: premium.active ? theme.colorScheme.primaryContainer : null,
      child: ListTile(
        leading: Icon(
          Icons.workspace_premium,
          color: premium.active
              ? theme.colorScheme.primary
              : theme.colorScheme.onSurfaceVariant,
        ),
        title: Text(text),
        subtitle: wallet.premiumFeatures.isEmpty
            ? null
            : Text(
                'Только с премиумом: '
                '${wallet.premiumFeatures.map(premiumFeatureLabel).join(', ')}',
              ),
        trailing: premium.active ? const PremiumBadge() : null,
      ),
    );
  }
}

class _ConsumableTile extends StatelessWidget {
  const _ConsumableTile({required this.item});

  final WalletItem item;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: ItemPreview(item: item),
      title: Text(item.displayTitle),
      subtitle: item.periodicQuantity > 0
          ? Text('еженедельных: ${item.periodicQuantity}')
          : null,
      trailing: Text(
        '×${item.quantity}',
        style: Theme.of(context).textTheme.titleMedium,
      ),
    );
  }
}

class _CosmeticTile extends StatelessWidget {
  const _CosmeticTile({
    required this.item,
    required this.equipped,
    required this.busy,
    required this.enabled,
    required this.onToggle,
  });

  final WalletItem item;
  final bool equipped;
  final bool busy;
  final bool enabled;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final stickers = asStringList(item.attributes['stickers']);

    final subtitleLines = <String>[
      if (stickers.isNotEmpty) 'Стикеры: ${stickers.join(', ')}',
      if (equipped) 'Надето',
    ];

    Widget? trailing;
    if (item.isEquippable) {
      if (busy) {
        trailing = const SizedBox.square(
          dimension: 24,
          child: CircularProgressIndicator(strokeWidth: 2),
        );
      } else if (equipped) {
        trailing = OutlinedButton(
          onPressed: enabled ? onToggle : null,
          child: const Text('Снять'),
        );
      } else {
        trailing = FilledButton.tonal(
          onPressed: enabled ? onToggle : null,
          child: const Text('Надеть'),
        );
      }
    }

    return ListTile(
      leading: ItemPreview(item: item),
      title: Wrap(
        spacing: 8,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(
            item.displayTitle,
            style: TextStyle(color: rarityColor(item.rarity)),
          ),
          RarityBadge(rarity: item.rarity),
        ],
      ),
      subtitle: subtitleLines.isEmpty
          ? null
          : Text(
              subtitleLines.join('\n'),
              style: equipped
                  ? TextStyle(color: theme.colorScheme.primary)
                  : null,
            ),
      trailing: trailing,
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(text, style: Theme.of(context).textTheme.titleMedium),
    );
  }
}

class _SubsectionHeader extends StatelessWidget {
  const _SubsectionHeader(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      child: Text(
        text,
        style: theme.textTheme.labelLarge?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}

class _EmptyHint extends StatelessWidget {
  const _EmptyHint(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Text(
        text,
        style: theme.textTheme.bodyMedium?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}
