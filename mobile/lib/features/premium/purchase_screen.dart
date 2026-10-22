import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/theme/app_theme.dart';

/// A control locked behind premium: taps on it open the purchase page instead of doing nothing.
Widget premiumGate({required bool locked, required VoidCallback onLockedTap, required Widget child}) => locked
    ? GestureDetector(behavior: HitTestBehavior.opaque, onTap: onLockedTap, child: AbsorbPointer(child: child))
    : child;

/// What the purchase page sells.
enum PurchaseKind { premium, superlikes, boosts }

/// Opens the purchase page ON TOP of the current screen; closing it returns exactly there.
/// Resolves to true when something was bought (the caller refreshes what it shows).
Future<bool> openPurchase(BuildContext context, {required DatingApiClient api, required PurchaseKind kind}) async {
  final bought = await Navigator.of(context).push<bool>(MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => PurchaseScreen(api: api, kind: kind),
  ));
  return bought ?? false;
}

class _Offer {
  const _Offer({
    required this.title,
    required this.lead,
    required this.points,
    required this.product,
    required this.button,
    required this.icon,
  });

  final String title;
  final String lead;
  final List<(IconData, String)> points;
  final String product;
  final String button;
  final IconData icon;
}

const Map<PurchaseKind, _Offer> _offers = {
  PurchaseKind.premium: _Offer(
    title: 'Премиум',
    lead: 'Больше способов найти своего человека — и спокойнее знакомиться.',
    icon: Icons.workspace_premium_outlined,
    product: 'PREMIUM_MONTH',
    button: 'Купить премиум на месяц',
    points: [
      (Icons.event_outlined, 'План встречи прямо в чате: место на карте, время, договориться в пару нажатий'),
      (Icons.star_outline_rounded, '5 суперлайков и буст каждую неделю'),
      (Icons.replay_rounded, 'Вернуть анкету, которую пролистнули случайно, и пропущенных — в любой момент'),
      (Icons.tune_rounded, 'Поиск по росту и слова-исключения: меньше неподходящих анкет'),
      (Icons.visibility_off_outlined, 'Невидимка: анкету видят только те, кого вы лайкнули'),
    ],
  ),
  PurchaseKind.superlikes: _Offer(
    title: 'Суперлайки',
    lead: 'Суперлайк показывает, что вы особенно заинтересованы: человек увидит вас среди первых.',
    icon: Icons.star_rounded,
    product: 'SUPERLIKE_PACK',
    button: 'Купить 5 суперлайков',
    points: [
      (Icons.trending_up_rounded, 'Вас замечают раньше остальных лайков'),
      (Icons.workspace_premium_outlined, 'С премиумом — 5 суперлайков каждую неделю'),
    ],
  ),
  PurchaseKind.boosts: _Offer(
    title: 'Бусты',
    lead: 'Буст на 30 минут чаще показывает вашу анкету тем, кому вы подходите.',
    icon: Icons.rocket_launch_rounded,
    product: 'BOOST_PACK',
    button: 'Купить 3 буста',
    points: [
      (Icons.visibility_outlined, 'Больше показов — больше шансов на взаимность'),
      (Icons.lock_outline_rounded, 'Другие не видят, что анкета в бусте'),
      (Icons.workspace_premium_outlined, 'С премиумом — буст каждую неделю'),
    ],
  ),
};

class PurchaseScreen extends StatefulWidget {
  const PurchaseScreen({super.key, required this.api, required this.kind});

  final DatingApiClient api;
  final PurchaseKind kind;

  @override
  State<PurchaseScreen> createState() => _PurchaseScreenState();
}

class _PurchaseScreenState extends State<PurchaseScreen> {
  bool _buying = false;
  String? _error;

  Future<void> _buy(_Offer offer) async {
    setState(() {
      _buying = true;
      _error = null;
    });
    try {
      // shortcut: a stub that grants the product without payment, until payments are decided.
      await widget.api.buyStub(offer.product);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${offer.title}: готово')));
      Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) {
        setState(() {
          _buying = false;
          _error = describeError(e);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final offer = _offers[widget.kind]!;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          tooltip: 'Закрыть',
          icon: const Icon(Icons.close_rounded),
          onPressed: () => Navigator.of(context).pop(false),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(Gaps.lg, 0, Gaps.lg, Gaps.lg),
        children: [
          Icon(offer.icon, size: 48, color: widget.kind == PurchaseKind.premium ? Brand.gold : Brand.rose),
          const SizedBox(height: Gaps.md),
          Text(offer.title, style: theme.textTheme.headlineMedium),
          const SizedBox(height: Gaps.sm),
          Text(offer.lead, style: theme.textTheme.bodyLarge),
          const SizedBox(height: Gaps.lg),
          for (final (icon, text) in offer.points)
            Padding(
              padding: const EdgeInsets.only(bottom: Gaps.md),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(icon, size: 22, color: theme.colorScheme.onSurface),
                  const SizedBox(width: Gaps.md),
                  Expanded(child: Text(text, style: theme.textTheme.bodyLarge)),
                ],
              ),
            ),
          if (widget.kind != PurchaseKind.premium)
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: () async {
                  final bought = await openPurchase(context, api: widget.api, kind: PurchaseKind.premium);
                  if (bought && context.mounted) Navigator.of(context).pop(true);
                },
                child: const Text('Что ещё даёт премиум'),
              ),
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
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: Gaps.sm),
                  child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
                ),
              FilledButton(
                onPressed: _buying ? null : () => _buy(offer),
                child: _buying
                    ? const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(offer.button),
              ),
              const SizedBox(height: Gaps.sm),
              Text('Оплата пока не подключена: покупка тестовая и бесплатная.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ],
          ),
        ),
      ),
    );
  }
}
