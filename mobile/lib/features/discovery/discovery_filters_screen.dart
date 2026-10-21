import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/models/discovery_filters.dart';
import '../../core/models/excluded_goals.dart';
import '../../core/models/goal.dart';
import '../../core/models/wallet.dart';
import '../auth/session_controller.dart';
import '../exclusion_words/exclusion_words_screen.dart';
import '../premium/premium_badge.dart';

/// Discovery filters: city and age are free; height, "looking for" goals,
/// saved excluded goals and exclusion words are premium.
///
/// The single entry point for everything filter-related in discovery.
///
/// Pops with the new [DiscoveryFilters] when the user taps "Применить", with
/// `null` on back. While premium is locked the height / goal controls are
/// disabled and their saved values are returned unchanged (kept, not sent). Saved server-side filters (excluded goals, exclusion
/// words) change the feed even without "Применить", so every successful save
/// is reported through [onSavedFiltersChanged].
class DiscoveryFiltersScreen extends StatefulWidget {
  const DiscoveryFiltersScreen({
    super.key,
    required this.session,
    this.initial = DiscoveryFilters.none,
    this.onSavedFiltersChanged,
  });

  final SessionController session;
  final DiscoveryFilters initial;
  final VoidCallback? onSavedFiltersChanged;

  @override
  State<DiscoveryFiltersScreen> createState() => _DiscoveryFiltersScreenState();
}

class _DiscoveryFiltersScreenState extends State<DiscoveryFiltersScreen> {
  static const String _premiumRequiredText =
      'Расширенный поиск доступен с премиумом. '
      'Город и возраст можно применить и без него.';

  late final TextEditingController _cityController;
  late RangeValues _age;
  late RangeValues _height;
  late Set<String> _goalCodes;

  // Premium section data (goals, saved excluded goals, wallet).
  bool _advancedLoading = true;
  String? _advancedError;
  List<Goal> _goals = const <Goal>[];
  Wallet? _wallet;
  List<String> _savedExcludedGoals = const <String>[];
  bool _excludedGoalsActive = true;
  Set<String> _excludedGoals = <String>{};

  /// The server answered 403 PREMIUM_REQUIRED during this visit.
  bool _premiumDenied = false;

  bool _applying = false;
  String? _applyError;

  @override
  void initState() {
    super.initState();
    final initial = widget.initial;
    _cityController = TextEditingController(text: initial.city ?? '');
    _age = RangeValues(
      (initial.minAge ?? DiscoveryFilters.ageMin).toDouble(),
      (initial.maxAge ?? DiscoveryFilters.ageMax).toDouble(),
    );
    _height = RangeValues(
      (initial.minHeight ?? DiscoveryFilters.heightMin).toDouble(),
      (initial.maxHeight ?? DiscoveryFilters.heightMax).toDouble(),
    );
    _goalCodes = initial.goalCodes.toSet();
    _loadAdvanced();
  }

  @override
  void dispose() {
    _cityController.dispose();
    super.dispose();
  }

  /// Height and goals are unavailable: premium-only and premium is off
  /// (by the wallet, the `active` flag of excluded goals or a 403).
  bool get _advancedLocked =>
      _premiumDenied ||
      !_excludedGoalsActive ||
      (_wallet?.isFeatureLocked(PremiumFeatureCodes.advancedFilters) ??
          false);

  bool get _exclusionWordsLocked =>
      _wallet?.isFeatureLocked(PremiumFeatureCodes.exclusionWords) ?? false;

  bool get _excludedGoalsChanged =>
      _excludedGoals.length != _savedExcludedGoals.length ||
      !_excludedGoals.containsAll(_savedExcludedGoals);

  Future<void> _loadAdvanced() async {
    final api = widget.session.api;
    // The wallet only refines the UI (the server enforces premium anyway),
    // so its failure is not an error.
    final walletFuture = api.getWallet().then<Wallet?>(
      (wallet) => wallet,
      onError: (Object e) {
        debugPrint('Failed to load wallet: $e');
        return null;
      },
    );
    try {
      final results = await Future.wait<Object>([
        api.getGoals(),
        api.getExcludedGoals(),
      ]);
      final goals = results[0] as List<Goal>;
      final excluded = results[1] as ExcludedGoals;
      final wallet = await walletFuture;
      if (!mounted) return;
      setState(() {
        _goals = goals;
        _wallet = wallet;
        _savedExcludedGoals = excluded.goalCodes;
        _excludedGoalsActive = excluded.active;
        _excludedGoals = excluded.goalCodes.toSet();
        _advancedLoading = false;
        _advancedError = null;
      });
    } catch (e) {
      await walletFuture;
      if (!mounted) return;
      // On 401 the SessionController already switches to the login screen.
      if (e is ApiException && e.isUnauthorized) return;
      setState(() {
        _advancedLoading = false;
        _advancedError = describeError(e);
      });
    }
  }

  void _retryAdvanced() {
    setState(() {
      _advancedLoading = true;
      _advancedError = null;
    });
    _loadAdvanced();
  }

  void _reset() {
    setState(() {
      _cityController.clear();
      _age = const RangeValues(
        DiscoveryFilters.ageMin * 1.0,
        DiscoveryFilters.ageMax * 1.0,
      );
      // Locked premium values cannot be edited here, so they are kept.
      if (!_advancedLocked) {
        _height = const RangeValues(
          DiscoveryFilters.heightMin * 1.0,
          DiscoveryFilters.heightMax * 1.0,
        );
        _goalCodes = <String>{};
      }
      _applyError = null;
    });
  }

  DiscoveryFilters _buildFilters() {
    final city = _cityController.text.trim();
    final minAge = _age.start.round();
    final maxAge = _age.end.round();
    final basic = DiscoveryFilters(
      city: city.isEmpty ? null : city,
      minAge: minAge > DiscoveryFilters.ageMin ? minAge : null,
      maxAge: maxAge < DiscoveryFilters.ageMax ? maxAge : null,
    );
    // Locked: the controls are disabled, keep the saved values as they are;
    // the feed does not send them until premium is back.
    if (_advancedLocked) {
      return DiscoveryFilters(
        city: basic.city,
        minAge: basic.minAge,
        maxAge: basic.maxAge,
        minHeight: widget.initial.minHeight,
        maxHeight: widget.initial.maxHeight,
        goalCodes: widget.initial.goalCodes,
      );
    }

    final minHeight = _height.start.round();
    final maxHeight = _height.end.round();
    return DiscoveryFilters(
      city: basic.city,
      minAge: basic.minAge,
      maxAge: basic.maxAge,
      // A bound at the slider edge means "no bound": sending it would also
      // hide profiles without a height.
      minHeight: minHeight > DiscoveryFilters.heightMin ? minHeight : null,
      maxHeight: maxHeight < DiscoveryFilters.heightMax ? maxHeight : null,
      goalCodes: _orderedCodes(_goalCodes),
    );
  }

  /// Codes in the order of [_goals]; unknown codes (not in the catalog)
  /// are kept at the end.
  List<String> _orderedCodes(Set<String> codes) {
    final known = _goals.map((goal) => goal.code).toSet();
    return [
      for (final goal in _goals)
        if (codes.contains(goal.code)) goal.code,
      for (final code in codes)
        if (!known.contains(code)) code,
    ];
  }

  Future<void> _apply() async {
    if (_applying) return;
    setState(() {
      _applying = true;
      _applyError = null;
    });

    if (!_advancedLoading &&
        _advancedError == null &&
        !_advancedLocked &&
        _excludedGoalsChanged) {
      try {
        final saved = await widget.session.api
            .updateExcludedGoals(_orderedCodes(_excludedGoals));
        if (!mounted) return;
        setState(() {
          _savedExcludedGoals = saved.goalCodes;
          _excludedGoalsActive = saved.active;
          _excludedGoals = saved.goalCodes.toSet();
        });
        widget.onSavedFiltersChanged?.call();
      } catch (e) {
        if (!mounted) return;
        if (e is ApiException && e.isUnauthorized) return;
        setState(() {
          _applying = false;
          if (e is ApiException && e.isPremiumRequired) {
            // Premium ended meanwhile: lock the section, keep the saved list.
            _premiumDenied = true;
            _excludedGoals = _savedExcludedGoals.toSet();
            _applyError = _premiumRequiredText;
          } else {
            _applyError = describeError(e, field: 'goalCodes');
          }
        });
        return;
      }
    }

    if (!mounted) return;
    Navigator.of(context).pop(_buildFilters());
  }

  Future<void> _openExclusionWords() async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(
        builder: (_) => ExclusionWordsScreen(session: widget.session),
      ),
    );
    if (!mounted) return;
    if (saved != true) return;
    widget.onSavedFiltersChanged?.call();
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Слова-исключения сохранены')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final applyError = _applyError;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Фильтры'),
        actions: [
          TextButton(
            onPressed: _applying ? null : _reset,
            child: const Text('Сбросить'),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: [
            ..._buildBasicSection(context),
            // TODO(ux): a future "Доп. фильтры" section goes here; its UX is
            // not decided yet, so nothing is implemented for it.
            const SizedBox(height: 24),
            const Divider(height: 1),
            const SizedBox(height: 16),
            ..._buildAdvancedSection(context),
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (applyError != null) ...[
                Text(
                  applyError,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
                const SizedBox(height: 8),
              ],
              FilledButton(
                onPressed: _applying ? null : _apply,
                child: _applying
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Text('Применить'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _buildBasicSection(BuildContext context) {
    final theme = Theme.of(context);
    return [
      Text('Основные', style: theme.textTheme.titleMedium),
      const SizedBox(height: 12),
      TextField(
        controller: _cityController,
        enabled: !_applying,
        textInputAction: TextInputAction.done,
        textCapitalization: TextCapitalization.words,
        decoration: const InputDecoration(
          labelText: 'Город',
          hintText: 'Любой',
        ),
      ),
      const SizedBox(height: 16),
      _RangeHeader(
        title: 'Возраст',
        value: _rangeText(
          _age,
          DiscoveryFilters.ageMin,
          DiscoveryFilters.ageMax,
          unit: '',
        ),
      ),
      RangeSlider(
        values: _age,
        min: DiscoveryFilters.ageMin.toDouble(),
        max: DiscoveryFilters.ageMax.toDouble(),
        divisions: DiscoveryFilters.ageMax - DiscoveryFilters.ageMin,
        labels: RangeLabels(
          '${_age.start.round()}',
          '${_age.end.round()}',
        ),
        onChanged: _applying ? null : (values) => setState(() => _age = values),
      ),
    ];
  }

  List<Widget> _buildAdvancedSection(BuildContext context) {
    final theme = Theme.of(context);
    final header = Row(
      children: [
        Expanded(
          child: Text(
            'Расширенный поиск · Премиум',
            style: theme.textTheme.titleMedium,
          ),
        ),
        const Icon(Icons.workspace_premium, size: 20),
      ],
    );

    if (_advancedLoading) {
      return [
        header,
        const Padding(
          padding: EdgeInsets.all(24),
          child: Center(child: CircularProgressIndicator()),
        ),
      ];
    }

    final advancedError = _advancedError;
    if (advancedError != null) {
      return [
        header,
        const SizedBox(height: 12),
        Text(advancedError),
        const SizedBox(height: 8),
        Align(
          alignment: Alignment.centerLeft,
          child: FilledButton.tonal(
            onPressed: _retryAdvanced,
            child: const Text('Повторить'),
          ),
        ),
      ];
    }

    final locked = _advancedLocked;
    final enabled = !locked && !_applying;
    final hiddenColor = theme.colorScheme.onSurfaceVariant;

    return [
      header,
      if (locked) ...[
        const SizedBox(height: 8),
        const PremiumLockedHint(),
      ],
      const SizedBox(height: 16),
      _RangeHeader(
        title: 'Рост',
        value: _rangeText(
          _height,
          DiscoveryFilters.heightMin,
          DiscoveryFilters.heightMax,
          unit: ' см',
        ),
      ),
      RangeSlider(
        values: _height,
        min: DiscoveryFilters.heightMin.toDouble(),
        max: DiscoveryFilters.heightMax.toDouble(),
        divisions: DiscoveryFilters.heightMax - DiscoveryFilters.heightMin,
        labels: RangeLabels(
          '${_height.start.round()} см',
          '${_height.end.round()} см',
        ),
        onChanged:
            enabled ? (values) => setState(() => _height = values) : null,
      ),
      const SizedBox(height: 8),
      Text('Ищу цели', style: theme.textTheme.titleSmall),
      const SizedBox(height: 4),
      Text(
        'Показывать анкеты хотя бы с одной из выбранных целей',
        style: theme.textTheme.bodySmall?.copyWith(color: hiddenColor),
      ),
      const SizedBox(height: 8),
      _GoalChips(
        goals: _goals,
        selected: _goalCodes,
        enabled: enabled,
        onChanged: (code, selected) => setState(() {
          selected ? _goalCodes.add(code) : _goalCodes.remove(code);
        }),
      ),
      const SizedBox(height: 16),
      Text('Скрывать цели', style: theme.textTheme.titleSmall),
      const SizedBox(height: 4),
      Text(
        'Анкеты с любой из этих целей не попадут в ленту. '
        'Сохраняется в аккаунте.',
        style: theme.textTheme.bodySmall?.copyWith(color: hiddenColor),
      ),
      const SizedBox(height: 8),
      _GoalChips(
        goals: _goals,
        selected: _excludedGoals,
        enabled: enabled,
        onChanged: (code, selected) => setState(() {
          selected ? _excludedGoals.add(code) : _excludedGoals.remove(code);
        }),
      ),
      if (!_excludedGoalsActive && _savedExcludedGoals.isNotEmpty) ...[
        const SizedBox(height: 8),
        const PremiumLockedHint(
          text: 'Сохранено, но не применяется без премиума',
        ),
      ],
      const SizedBox(height: 16),
      Card(
        margin: EdgeInsets.zero,
        child: ListTile(
          leading: const Icon(Icons.block),
          title: const Text('Слова-исключения'),
          subtitle: Text(
            _exclusionWordsLocked
                ? 'Доступно с премиумом'
                : 'Скрывать анкеты по словам в «О себе» и статусе',
          ),
          trailing: const Icon(Icons.chevron_right),
          onTap: _applying ? null : _openExclusionWords,
        ),
      ),
    ];
  }

  static String _rangeText(
    RangeValues values,
    int min,
    int max, {
    required String unit,
  }) {
    final from = values.start.round();
    final to = values.end.round();
    if (from <= min && to >= max) return 'Любой';
    if (from <= min) return 'до $to$unit';
    if (to >= max) return 'от $from$unit';
    return '$from–$to$unit';
  }
}

class _RangeHeader extends StatelessWidget {
  const _RangeHeader({required this.title, required this.value});

  final String title;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: [
        Expanded(child: Text(title, style: theme.textTheme.titleSmall)),
        Text(value, style: theme.textTheme.bodyMedium),
      ],
    );
  }
}

class _GoalChips extends StatelessWidget {
  const _GoalChips({
    required this.goals,
    required this.selected,
    required this.enabled,
    required this.onChanged,
  });

  final List<Goal> goals;
  final Set<String> selected;
  final bool enabled;
  final void Function(String code, bool selected) onChanged;

  @override
  Widget build(BuildContext context) {
    if (goals.isEmpty) {
      return const Text('Список целей пуст');
    }
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final goal in goals)
          FilterChip(
            label: Text(goal.displayLabel),
            selected: selected.contains(goal.code),
            onSelected:
                enabled ? (value) => onChanged(goal.code, value) : null,
          ),
      ],
    );
  }
}
