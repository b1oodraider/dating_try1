import 'package:flutter/material.dart';
import 'social_test_card.dart';
import 'package:flutter/services.dart';

import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/achievement.dart';
import '../../core/models/testing_state.dart';
import '../../core/models/wallet.dart';
import '../auth/session_controller.dart';
import '../cosmetics/cosmetic_visuals.dart';
import 'stub_catalog.dart';

/// QA tools for test accounts (`/testing/*`). Opened from the feed only when
/// `Account.testAccount` is true; the server checks it again (403
/// TEST_ACCOUNT_REQUIRED) and the endpoints exist only with
/// app.testing.enabled=true.
class TestPanelScreen extends StatefulWidget {
  const TestPanelScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<TestPanelScreen> createState() => _TestPanelScreenState();
}

class _TestPanelScreenState extends State<TestPanelScreen> {
  static const int _nextWeekDays = 7;
  static const int _maxDays = 3650;

  final TextEditingController _codeController = TextEditingController();
  final TextEditingController _quantityController =
      TextEditingController(text: '1');
  final TextEditingController _premiumDaysController =
      TextEditingController(text: '7');

  TestingState? _state;
  bool _loading = true;
  String? _loadError;

  /// A testing request is in flight; all actions are disabled meanwhile.
  bool _busy = false;

  String? _codeError;
  String? _quantityError;
  String? _premiumDaysError;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _codeController.dispose();
    _quantityController.dispose();
    _premiumDaysController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final state = await widget.session.api.getTestingState();
      if (!mounted) return;
      setState(() {
        _state = state;
        _loading = false;
        _loadError = null;
      });
    } catch (e) {
      if (!mounted) return;
      // On 401 the SessionController already switches to the login screen.
      if (e is ApiException && e.isUnauthorized) return;
      setState(() {
        _loading = false;
        _loadError = _errorText(e);
      });
    }
  }

  void _retryLoad() {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    _load();
  }

  /// Runs one testing call; the returned state replaces the shown one.
  Future<void> _run(
    Future<TestingState> Function() call, {
    String? done,
  }) async {
    if (_busy) return;
    FocusScope.of(context).unfocus();
    setState(() => _busy = true);
    try {
      final state = await call();
      if (!mounted) return;
      setState(() {
        _state = state;
        _busy = false;
      });
      if (done != null) _showMessage(done);
    } catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      if (e is ApiException && e.isUnauthorized) return;
      _showMessage(_errorText(e), isError: true);
    }
  }

  /// Server message plus the machine code, so QA can tell errors apart.
  static String _errorText(Object error) {
    if (error is ApiException) {
      final status = error.statusCode > 0 ? ' · HTTP ${error.statusCode}' : '';
      return '${error.message}\n${error.code}$status';
    }
    return describeError(error);
  }

  void _showMessage(String message, {bool isError = false}) {
    final scheme = Theme.of(context).colorScheme;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(
            message,
            style: isError ? TextStyle(color: scheme.onErrorContainer) : null,
          ),
          backgroundColor: isError ? scheme.errorContainer : null,
        ),
      );
  }

  // ------------------------------------------------------------ Actions

  void _grantItem() {
    final form = _readItemForm(minQuantity: 1);
    if (form == null) return;
    _run(
      () => widget.session.api.grantTestItem(form.code, form.quantity),
      done: 'Начислено: ${form.code} ×${form.quantity}',
    );
  }

  void _setItemBalance() {
    final form = _readItemForm(minQuantity: 0);
    if (form == null) return;
    _run(
      () => widget.session.api.setTestItemBalance(form.code, form.quantity),
      done: 'Баланс ${form.code}: ${form.quantity}',
    );
  }

  ({String code, int quantity})? _readItemForm({required int minQuantity}) {
    final code = _codeController.text.trim();
    final quantity = int.tryParse(_quantityController.text.trim());
    final codeError = code.isEmpty ? 'Укажите код предмета' : null;
    final quantityError = (quantity == null || quantity < minQuantity)
        ? 'Целое число не меньше $minQuantity'
        : null;
    setState(() {
      _codeError = codeError;
      _quantityError = quantityError;
    });
    if (codeError != null || quantityError != null || quantity == null) {
      return null;
    }
    return (code: code, quantity: quantity);
  }

  void _addPremiumDays() {
    final days = int.tryParse(_premiumDaysController.text.trim());
    if (days == null || days < 1 || days > _maxDays) {
      setState(() => _premiumDaysError = 'От 1 до $_maxDays дней');
      return;
    }
    setState(() => _premiumDaysError = null);
    _run(
      () => widget.session.api.addTestPremiumDays(days),
      done: 'Премиум продлён на $days дн.',
    );
  }

  void _removePremium() {
    _run(
      widget.session.api.removeTestPremium,
      done: 'Премиум снят',
    );
  }

  void _nextWeek() {
    _run(
      () => widget.session.api.advanceTestClock(_nextWeekDays),
      done: 'Часы сдвинуты на $_nextWeekDays дн.',
    );
  }

  void _resetClock() {
    _run(
      widget.session.api.resetTestClock,
      done: 'Часы возвращены к реальному времени',
    );
  }

  void _toggleAchievement(Achievement achievement) {
    final api = widget.session.api;
    if (achievement.completed) {
      _run(
        () => api.resetTestAchievement(achievement.code),
        done: 'Сброшено: ${achievement.displayTitle}',
      );
    } else {
      _run(
        () => api.completeTestAchievement(achievement.code),
        done: 'Выполнено: ${achievement.displayTitle}',
      );
    }
  }

  void _pickCode(String code) {
    setState(() {
      _codeController.text = code;
      _codeError = null;
    });
  }

  // ------------------------------------------------------------ UI

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Тест-панель'),
        actions: [
          IconButton(
            tooltip: 'Обновить',
            icon: const Icon(Icons.refresh),
            onPressed: (_busy || _state == null)
                ? null
                : () => _run(widget.session.api.getTestingState),
          ),
        ],
        bottom: _busy
            ? const PreferredSize(
                preferredSize: Size.fromHeight(4),
                child: LinearProgressIndicator(),
              )
            : null,
      ),
      body: SafeArea(child: _buildBody(context)),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }

    final loadError = _loadError;
    final state = _state;
    if (loadError != null || state == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.science_outlined, size: 48),
              const SizedBox(height: 12),
              Text(
                loadError ?? 'Не удалось загрузить состояние',
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

    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        SocialTestCard(api: widget.session.api),
        _buildClockCard(context, state),
        _buildPremiumCard(context, state.wallet),
        _buildItemsCard(context, state.wallet),
        _buildAchievementsCard(context, state.achievements),
      ],
    );
  }

  Widget _buildClockCard(BuildContext context, TestingState state) {
    final theme = Theme.of(context);
    final now = state.economyNow;
    final offset = state.clockOffsetDays;
    return _Section(
      title: 'Время экономики',
      children: [
        Text(
          now == null ? 'Время: —' : 'Сейчас: ${formatDateTime(now)}',
          style: theme.textTheme.bodyLarge,
        ),
        Text(
          offset == 0
              ? 'Сдвиг часов: нет'
              : 'Сдвиг часов: +${formatDays(offset)} дн.',
        ),
        const SizedBox(height: 4),
        SelectableText(
          'Аккаунт: ${state.accountId}',
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            FilledButton.tonalIcon(
              onPressed: _busy ? null : _nextWeek,
              icon: const Icon(Icons.fast_forward),
              label: const Text('Следующая неделя'),
            ),
            OutlinedButton(
              onPressed: (_busy || offset == 0) ? null : _resetClock,
              child: const Text('Сбросить часы'),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildPremiumCard(BuildContext context, Wallet wallet) {
    final premium = wallet.premium;
    final until = premium.until;
    final String status;
    if (!premium.active) {
      status = 'Не активен';
    } else if (until != null) {
      status = 'Активен до ${formatDateTime(until)}';
    } else {
      status = 'Активен';
    }

    return _Section(
      title: 'Премиум',
      children: [
        Text(status, style: Theme.of(context).textTheme.bodyLarge),
        const SizedBox(height: 12),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 120,
              child: TextField(
                controller: _premiumDaysController,
                enabled: !_busy,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(
                  labelText: 'Дней',
                  isDense: true,
                  errorText: _premiumDaysError,
                  errorMaxLines: 2,
                ),
                onSubmitted: (_) => _addPremiumDays(),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.tonal(
                    onPressed: _busy ? null : _addPremiumDays,
                    child: const Text('+ дни'),
                  ),
                  OutlinedButton(
                    onPressed:
                        (_busy || !premium.active) ? null : _removePremium,
                    child: const Text('Снять'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _buildItemsCard(BuildContext context, Wallet wallet) {
    final theme = Theme.of(context);
    final choices = <String>[
      ...stubItemCodes,
      for (final item in wallet.items)
        if (!stubItemCodes.contains(item.code)) item.code,
    ];

    return _Section(
      title: 'Предметы',
      children: [
        TextField(
          controller: _codeController,
          enabled: !_busy,
          autocorrect: false,
          textCapitalization: TextCapitalization.characters,
          onChanged: (_) {
            if (_codeError != null) setState(() => _codeError = null);
          },
          decoration: InputDecoration(
            labelText: 'Код предмета',
            hintText: 'выберите или введите',
            isDense: true,
            errorText: _codeError,
            suffixIcon: PopupMenuButton<String>(
              tooltip: 'Коды из каталога',
              enabled: !_busy,
              icon: const Icon(Icons.arrow_drop_down),
              onSelected: _pickCode,
              itemBuilder: (_) => [
                for (final code in choices)
                  PopupMenuItem<String>(value: code, child: Text(code)),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 120,
              child: TextField(
                controller: _quantityController,
                enabled: !_busy,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                onChanged: (_) {
                  if (_quantityError != null) {
                    setState(() => _quantityError = null);
                  }
                },
                decoration: InputDecoration(
                  labelText: 'Количество',
                  isDense: true,
                  errorText: _quantityError,
                  errorMaxLines: 2,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.tonal(
                    onPressed: _busy ? null : _grantItem,
                    child: const Text('Начислить'),
                  ),
                  OutlinedButton(
                    onPressed: _busy ? null : _setItemBalance,
                    child: const Text('Установить баланс'),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          '«Начислить» добавляет к балансу (≥ 1), «Установить баланс» задаёт '
          'купленную часть точно (0 — убрать).',
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 8),
        if (wallet.items.isEmpty)
          const Text('Кошелёк пуст')
        else
          for (final item in wallet.items)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: ItemPreview(item: item),
              title: Text(item.displayTitle),
              subtitle: Text(_itemDetails(wallet, item)),
              trailing: Text(
                '×${item.quantity}',
                style: theme.textTheme.titleMedium,
              ),
              onTap: _busy ? null : () => _pickCode(item.code),
            ),
      ],
    );
  }

  static String _itemDetails(Wallet wallet, WalletItem item) {
    return [
      item.code,
      if (item.periodicQuantity > 0) 'еженедельных: ${item.periodicQuantity}',
      if (wallet.isEquipped(item)) 'надето',
    ].join(' · ');
  }

  Widget _buildAchievementsCard(
    BuildContext context,
    List<Achievement> achievements,
  ) {
    final theme = Theme.of(context);
    return _Section(
      title: 'Достижения',
      children: [
        if (achievements.isEmpty) const Text('Достижений нет'),
        for (final achievement in achievements)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  achievement.completed
                      ? Icons.check_circle
                      : Icons.radio_button_unchecked,
                  color: achievement.completed
                      ? theme.colorScheme.primary
                      : theme.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        achievement.displayTitle,
                        style: theme.textTheme.titleSmall,
                      ),
                      if ((achievement.description ?? '').isNotEmpty)
                        Text(achievement.description!),
                      if (achievement.rewards.isNotEmpty)
                        Text(
                          'Награда: '
                          '${achievement.rewards.map(describeReward).join(', ')}',
                          style: theme.textTheme.bodySmall,
                        ),
                      Text(
                        _completionText(achievement),
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 8),
                achievement.completed
                    ? OutlinedButton(
                        onPressed: _busy
                            ? null
                            : () => _toggleAchievement(achievement),
                        child: const Text('Сбросить'),
                      )
                    : FilledButton.tonal(
                        onPressed: _busy
                            ? null
                            : () => _toggleAchievement(achievement),
                        child: const Text('Выполнить'),
                      ),
              ],
            ),
          ),
      ],
    );
  }

  static String _completionText(Achievement achievement) {
    if (!achievement.completed) return 'Не выполнено';
    final at = achievement.completedAt;
    return at == null ? 'Выполнено' : 'Выполнено ${formatDateTime(at)}';
  }
}

/// Human-readable reward, e.g. "SUPERLIKE ×1" or "премиум +3 дн.".
String describeReward(AchievementReward reward) {
  switch (reward.type) {
    case AchievementReward.typeItem:
      return '${reward.itemCode ?? '?'} ×${reward.quantity ?? 1}';
    case AchievementReward.typePremiumDays:
      return 'премиум +${reward.days ?? 0} дн.';
  }
  return reward.type.isEmpty ? '?' : reward.type;
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            ...children,
          ],
        ),
      ),
    );
  }
}
