import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/my_profile.dart';
import '../../core/theme/app_theme.dart';
import 'profile_patch.dart';
import 'profile_steps.dart';

enum WizardMode {
  /// First fill-in after sign-up: starts from what is there, optional steps can be skipped.
  onboarding,

  /// «Заполнить заново»: every step starts empty; «Оставить текущее» keeps the old value. Nothing is erased on
  /// the server: old values stay in the profile history, old photos stay for comparison.
  refill,
}

/// The profile step by step. Each step is saved on «Далее»; pops with `true` when finished.
class ProfileWizardScreen extends StatefulWidget {
  const ProfileWizardScreen({
    super.key,
    required this.api,
    required this.profile,
    required this.mode,
  });

  final DatingApiClient api;
  final MyProfile profile;
  final WizardMode mode;

  @override
  State<ProfileWizardScreen> createState() => _ProfileWizardScreenState();
}

class _ProfileWizardScreenState extends State<ProfileWizardScreen> {
  late MyProfile _profile = widget.profile;
  late final List<ProfileStep> _steps = [
    for (final s in profileSteps)
      if (!(s.id == 'identity' && widget.profile.identityLocked)) s,
  ];
  int _index = 0;
  ProfilePatch _pending = ProfilePatch.empty;
  Map<String, String> _errors = const <String, String>{};
  bool _saving = false;

  bool get _refill => widget.mode == WizardMode.refill;
  ProfileStep get _step => _steps[_index];
  bool get _last => _index == _steps.length - 1;

  void _go(int index) => setState(() {
        _index = index;
        _pending = ProfilePatch.empty;
        _errors = const <String, String>{};
      });

  Future<void> _next({bool keep = false}) async {
    if (!keep && !_pending.isEmpty) {
      setState(() => _saving = true);
      try {
        final saved = await _pending.saveWith(widget.api);
        if (!mounted) return;
        setState(() => _profile = saved);
      } catch (e) {
        if (!mounted) return;
        setState(() => _errors = e is ApiException ? e.fieldErrors : const <String, String>{});
        if (_errors.isEmpty) _snack(describeError(e));
        return;
      } finally {
        if (mounted) setState(() => _saving = false);
      }
    }
    if (!_last) {
      _go(_index + 1);
      return;
    }
    await _finish();
  }

  Future<void> _finish() async {
    if (_profile.isPublished) {
      if (mounted) Navigator.of(context).pop(true);
      return;
    }
    final missing = _steps.indexWhere((s) => s.requiredToPublish && !s.hasValue(_profile));
    if (missing >= 0) {
      _snack('Чтобы показать анкету в ленте, заполните: ${_steps[missing].title.toLowerCase()}');
      _go(missing);
      return;
    }
    setState(() => _saving = true);
    try {
      await widget.api.publishProfile();
      if (!mounted) return;
      _snack('Анкета опубликована — её видят в ленте');
      Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _snack(String text) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _back() async {
    if (_index > 0) {
      _go(_index - 1);
      return;
    }
    final leave = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Выйти из заполнения?'),
        content: const Text('Пройденные шаги уже сохранены.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Остаться')),
          FilledButton(onPressed: () => Navigator.of(c).pop(true), child: const Text('Выйти')),
        ],
      ),
    );
    if (leave == true && mounted) Navigator.of(context).pop(false);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final step = _step;
    final hasCurrent = step.hasValue(_profile);
    final hasNew = !_pending.isEmpty;
    // A required step needs a value before going on; «Далее» on an instant step (photos) just moves on.
    final canNext = !_saving &&
        (step.instant
            ? (!step.requiredToPublish || hasCurrent || widget.mode == WizardMode.refill)
            : hasNew || (!_refill && (hasCurrent || !step.requiredToPublish)));
    final current = step.summary(_profile);

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _back();
      },
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(icon: const Icon(Icons.arrow_back_rounded), onPressed: _back),
          title: Text('Шаг ${_index + 1} из ${_steps.length}', style: theme.textTheme.titleMedium),
          bottom: PreferredSize(
            preferredSize: const Size.fromHeight(4),
            child: LinearProgressIndicator(
              value: (_index + 1) / _steps.length,
              backgroundColor: theme.colorScheme.outlineVariant,
            ),
          ),
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(Gaps.lg, Gaps.lg, Gaps.lg, Gaps.xl),
          children: [
            Text(step.question, style: theme.textTheme.headlineSmall),
            if (_refill && hasCurrent && current.isNotEmpty) ...[
              const SizedBox(height: Gaps.sm),
              Text('Сейчас: $current',
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ],
            const SizedBox(height: Gaps.lg),
            KeyedSubtree(
              key: ValueKey('${step.id}-$_index'),
              child: step.build(StepContext(
                api: widget.api,
                profile: _profile,
                blank: _refill,
                onChanged: (p) => setState(() => _pending = _pending.merge(p)),
                onProfile: (p) => setState(() => _profile = p),
                errors: _errors,
              )),
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
                FilledButton(
                  onPressed: canNext ? () => _next() : null,
                  child: _saving
                      ? const SizedBox.square(dimension: 20, child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(_last ? 'Готово' : 'Далее'),
                ),
                if (_refill && hasCurrent && !step.instant)
                  TextButton(
                    onPressed: _saving ? null : () => _next(keep: true),
                    child: const Text('Оставить текущее'),
                  )
                else if (!_refill && !step.requiredToPublish && !hasNew)
                  TextButton(
                    onPressed: _saving ? null : () => _next(keep: true),
                    child: const Text('Пропустить'),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// First run: three short screens about the app, then the profile wizard. Pops with `true` once published.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.api, required this.profile});

  final DatingApiClient api;
  final MyProfile profile;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final PageController _pages = PageController();
  int _page = 0;

  static const _slides = [
    (Icons.favorite_rounded, 'Знакомства без спешки',
        'Свайпайте анкеты: вправо — нравится, влево — нет. При взаимной симпатии откроется чат.'),
    (Icons.verified_user_rounded, 'Здесь живые люди',
        'Подтвердите селфи — получите значок «Проверен» и больше доверия. Фото без человека не загрузятся.'),
    (Icons.auto_awesome_rounded, 'Чем больше о вас, тем точнее подбор',
        'Увлечения, музыка и цели помогают найти тех, с кем у вас правда много общего.'),
  ];

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    final done = await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
      builder: (_) =>
          ProfileWizardScreen(api: widget.api, profile: widget.profile, mode: WizardMode.onboarding),
    ));
    if (mounted && done == true) Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final last = _page == _slides.length - 1;
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Позже'),
              ),
            ),
            Expanded(
              child: PageView(
                controller: _pages,
                onPageChanged: (i) => setState(() => _page = i),
                children: [
                  for (final (icon, title, text) in _slides)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: Gaps.xl),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Container(
                            width: 120,
                            height: 120,
                            decoration: BoxDecoration(
                              gradient: Brand.gradient,
                              borderRadius: BorderRadius.circular(36),
                            ),
                            child: Icon(icon, color: Colors.white, size: 60),
                          ),
                          const SizedBox(height: Gaps.xl),
                          Text(title, textAlign: TextAlign.center, style: theme.textTheme.headlineSmall),
                          const SizedBox(height: Gaps.md),
                          Text(text,
                              textAlign: TextAlign.center,
                              style: theme.textTheme.bodyLarge
                                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                        ],
                      ),
                    ),
                ],
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                for (var i = 0; i < _slides.length; i++)
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    margin: const EdgeInsets.symmetric(horizontal: 4),
                    width: i == _page ? 22 : 8,
                    height: 8,
                    decoration: BoxDecoration(
                      color: i == _page ? theme.colorScheme.primary : theme.colorScheme.outlineVariant,
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
              ],
            ),
            Padding(
              padding: const EdgeInsets.all(Gaps.lg),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: last
                      ? _start
                      : () => _pages.nextPage(
                          duration: const Duration(milliseconds: 250), curve: Curves.easeOut),
                  child: Text(last ? 'Заполнить анкету' : 'Дальше'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
