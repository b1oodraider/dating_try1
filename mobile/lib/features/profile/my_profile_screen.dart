import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/account.dart';
import '../../core/models/community.dart';
import '../../core/models/facts.dart';
import '../../core/models/my_profile.dart';
import '../../core/models/social.dart';
import '../../core/theme/app_theme.dart';
import '../auth/session_controller.dart';
import '../collection/collection_screen.dart';
import '../discovery/profile_card.dart';
import '../discovery/profile_preview.dart';
import '../safety/verification_intro_screen.dart';
import '../support/support_screen.dart';
import '../testing/test_panel_screen.dart';
import 'profile_saver.dart';
import 'profile_section_screen.dart';
import 'profile_steps.dart';
import 'profile_wizard.dart';
import 'settings_screen.dart';

const Map<String, String> _restrictionText = {
  'LIKES': 'Лайки временно недоступны',
  'MESSAGES': 'Сообщения временно недоступны',
  'HIDDEN': 'Анкета скрыта из ленты',
  'SUSPENDED': 'Аккаунт заблокирован',
  'VERIFICATION_REQUIRED': 'Анкета скрыта до проверки селфи',
};

const Map<String, String> _missingText = {
  'displayName': 'имя',
  'birthDate': 'дата рождения',
  'gender': 'пол',
  'seeking': 'кого ищете (в фильтрах)',
  'city': 'город поиска (в фильтрах)',
  'photos': 'фото',
};

/// My profile as it reads: photos, «О себе» right below (saved as you type), goals, then each section shown as
/// it is with «Редактировать». Who I look for and the search city are in the filters; my location, visibility and
/// the account are in the settings (gear).
class MyProfileScreen extends StatefulWidget {
  const MyProfileScreen({super.key, required this.session, this.onOpenGroups});

  final SessionController session;

  /// «Изменить» under my groups: the «Интересы» tab.
  final VoidCallback? onOpenGroups;

  @override
  State<MyProfileScreen> createState() => _MyProfileScreenState();
}

class _MyProfileScreenState extends State<MyProfileScreen> {
  /// Holds the latest profile and saves «О себе» typed right here.
  ProfileSaver? _saver;
  Account? _account;
  List<Restriction> _restrictions = const <Restriction>[];
  List<Community> _groups = const <Community>[];
  bool _loading = true;
  bool _busy = false;
  String? _error;

  MyProfile? get _profile => _saver?.profile;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _saver?.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final api = widget.session.api;
      final results = await Future.wait<Object>(
          [api.getMyProfile(), api.getMe(), api.getRestrictions(), api.getMyCommunities()]);
      if (!mounted) return;
      final profile = results[0] as MyProfile;
      setState(() {
        if (_saver == null) {
          _saver = ProfileSaver(api, profile, onError: _snack)..addListener(_onSaved);
        } else {
          _saver!.replace(profile);
        }
        _account = results[1] as Account;
        _restrictions = results[2] as List<Restriction>;
        _groups = results[3] as List<Community>;
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

  void _onSaved() {
    if (mounted) setState(() {});
  }

  void _snack(String text) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  Future<void> _openSection(ProfileStep step) async {
    final profile = _profile;
    if (profile == null) return;
    await _saver!.flush();
    if (!mounted) return;
    final updated = await Navigator.of(context).push<MyProfile>(MaterialPageRoute<MyProfile>(
      builder: (_) => ProfileSectionScreen(api: widget.session.api, step: step, profile: _profile!),
    ));
    if (!mounted) return;
    if (updated != null) _saver!.replace(updated);
    _load();
  }

  Future<void> _open(String stepId) => _openSection(profileSteps.firstWhere((s) => s.id == stepId));

  Future<void> _refill() async {
    final profile = _profile;
    if (profile == null) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Заполнить анкету заново?'),
        content: const Text('Пройдёте все шаги с чистого листа. На каждом шаге можно оставить текущее. Фото и '
            'прежние данные не теряются.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.of(c).pop(true), child: const Text('Начать')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
      builder: (_) => ProfileWizardScreen(api: widget.session.api, profile: profile, mode: WizardMode.refill),
    ));
    if (mounted) _load();
  }

  Future<void> _publish() async {
    setState(() => _busy = true);
    try {
      final updated = await widget.session.api.publishProfile();
      if (!mounted) return;
      _saver!.replace(updated);
      _snack('Анкета опубликована — её видят в ленте');
    } catch (e) {
      _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// How much each section would add to completeness (undone tips), keyed by step id.
  static Map<String, int> _gains(MyProfile profile) {
    const stepOf = {
      'NAME': 'name', 'AGE': 'identity', 'CITY': 'place', 'FIRST_PHOTO': 'photos', 'MORE_PHOTOS': 'photos',
      'ABOUT': 'about', 'CARDS': 'tags', 'GOALS': 'goals', 'HEIGHT': 'identity', 'VERIFIED': 'verified',
    };
    final gains = <String, int>{};
    for (final tip in profile.tips.where((t) => !t.done)) {
      final step = stepOf[tip.code];
      if (step != null) gains[step] = (gains[step] ?? 0) + tip.weight;
    }
    return gains;
  }

  Future<void> _push(Widget screen) async {
    await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => screen));
    if (mounted) _load();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(
          title: const Text('Моя анкета'),
          actions: [
            IconButton(
              tooltip: 'Помощь',
              icon: const Icon(Icons.help_outline_rounded),
              onPressed: () => _push(SupportScreen(api: widget.session.api)),
            ),
            IconButton(
              tooltip: 'Настройки',
              icon: const Icon(Icons.settings_outlined),
              onPressed: _profile == null
                  ? null
                  : () => _push(SettingsScreen(session: widget.session, profile: _profile!, account: _account)),
            ),
          ],
        ),
        body: _buildBody(),
      );

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final profile = _profile;
    final saver = _saver;
    if (profile == null || saver == null) {
      return Center(
        child: TextButton(
          onPressed: _load,
          child: Text('${_error ?? 'Ошибка'}\nПовторить', textAlign: TextAlign.center),
        ),
      );
    }
    final theme = Theme.of(context);
    final api = widget.session.api;
    final gains = _gains(profile);
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.xl),
        children: [
          if (!profile.verified) ...[
            _VerifyBanner(gain: gains['verified'], onTap: () => _push(VerificationIntroScreen(api: api))),
            const SizedBox(height: Gaps.md),
          ],
          for (final r in _restrictions)
            Card(
              color: theme.colorScheme.errorContainer,
              child: ListTile(
                leading: Icon(Icons.shield_outlined, color: theme.colorScheme.onErrorContainer),
                title: Text(_restrictionText[r.kind] ?? r.kind),
                subtitle: Text(r.endsAt == null ? 'До решения модератора' : 'До ${formatDateTime(r.endsAt!)}'),
              ),
            ),
          _Header(
            profile: profile,
            photoUrl: api.resolveMediaUrl,
            missing: profile.missingForPublish.map((f) => _missingText[f] ?? f).toList(),
            onPublish: _busy ? null : _publish,
            onPreview: () => showMyProfileAsOthers(context, api: api, profile: profile),
            onRefill: _refill,
          ),
          const SizedBox(height: Gaps.lg),
          _PhotoStrip(
            profile: profile,
            gain: gains['photos'],
            photoUrl: api.resolveMediaUrl,
            onOpen: () => _open('photos'),
          ),
          const SizedBox(height: Gaps.lg),
          Row(
            children: [
              const Expanded(child: _Section('О себе')),
              if (gains['about'] case final gain?) _GainText(gain),
              Padding(
                padding: const EdgeInsets.only(left: Gaps.sm, bottom: Gaps.sm),
                child: Text(saver.statusText,
                    style: theme.textTheme.labelMedium?.copyWith(
                        color: saver.state == SaveState.failed
                            ? theme.colorScheme.error
                            : theme.colorScheme.onSurfaceVariant)),
              ),
            ],
          ),
          AboutEditor(inline: true, StepContext(
            api: api,
            profile: profile,
            onChanged: saver.onChanged,
            onProfile: saver.replace,
            errors: saver.errors,
          )),
          const SizedBox(height: Gaps.md),
          _Block(
            title: 'Цели знакомства',
            gain: gains['goals'],
            onEdit: () => _open('goals'),
            child: profile.goals.isEmpty ? null : _chips([for (final g in profile.goals) g.displayLabel]),
          ),
          _Block(
            title: 'Основное',
            gain: gains['name'] ?? gains['identity'],
            onEdit: null,
            child: Column(
              children: [
                for (final id in const ['name', 'identity'])
                  if (profileSteps.firstWhere((s) => s.id == id) case final step)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(step.icon),
                      title: Text(step.title),
                      subtitle: Text(step.hasValue(profile) ? step.summary(profile) : 'Не заполнено',
                          style: step.hasValue(profile) ? null : TextStyle(color: theme.colorScheme.error)),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      onTap: () => _openSection(step),
                    ),
              ],
            ),
          ),
          _Block(
            title: 'Карточки общения',
            gain: gains['tags'],
            onEdit: () => _open('tags'),
            child: profile.tags.isEmpty ? null : _chips([for (final t in profile.tags) t.label]),
          ),
          _Block(
            title: 'Увлечения',
            onEdit: () => _open('interests'),
            child: profile.interests.isEmpty
                ? null
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _chips([for (final i in profile.interests) i.display]),
                      for (final i in profile.interests)
                        if (i.comment case final comment?)
                          Padding(
                            padding: const EdgeInsets.only(top: Gaps.sm),
                            child: Text.rich(TextSpan(children: [
                              TextSpan(text: '${i.display} — ', style: const TextStyle(fontWeight: FontWeight.w600)),
                              TextSpan(text: comment),
                            ])),
                          ),
                    ],
                  ),
          ),
          _Block(
            title: 'Музыка',
            onEdit: () => _open('music'),
            child: profile.artists.isEmpty ? null : _chips([for (final a in profile.artists) a.name]),
          ),
          _Block(
            title: 'Группы',
            onEdit: widget.onOpenGroups,
            child: _groups.isEmpty ? null : _chips([for (final g in _groups) g.display]),
          ),
          for (final (id, title, keys) in _factBlocks)
            _Block(
              title: title,
              onEdit: () => _open(id),
              child: keys.any(profile.facts.values.containsKey)
                  ? FutureBuilder(
                      future: Dictionaries.options(api),
                      builder: (context, snapshot) => Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (snapshot.data case final options?)
                            for (final key in keys)
                              if (options.describe(profile.facts, key) case final text?)
                                Padding(
                                  padding: const EdgeInsets.only(bottom: Gaps.xs),
                                  child: Text.rich(TextSpan(children: [
                                    TextSpan(
                                        text: '${Facts.titles[key] ?? key}: ',
                                        style: TextStyle(color: theme.colorScheme.onSurfaceVariant)),
                                    TextSpan(text: text),
                                  ])),
                                ),
                        ],
                      ),
                    )
                  : null,
            ),
          const SizedBox(height: Gaps.sm),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.collections_bookmark_outlined),
                  title: const Text('Коллекция и премиум'),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => _push(CollectionScreen(session: widget.session)),
                ),
                if (_account?.testAccount ?? false)
                  ListTile(
                    leading: const Icon(Icons.science_outlined),
                    title: const Text('Тест-панель'),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => _push(TestPanelScreen(session: widget.session)),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static Widget _chips(List<String> labels) => Wrap(
        spacing: Gaps.sm,
        runSpacing: Gaps.sm,
        children: [for (final l in labels) Chip(label: Text(l), visualDensity: VisualDensity.compact)],
      );
}

const List<(String, String, List<String>)> _factBlocks = [
  ('lifestyle', 'Образ жизни', ['kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'pets']),
  ('work', 'Работа и учёба', ['profession', 'educationLevel', 'university', 'languages']),
];

/// «Подтвердите анкету»: at the very top until the selfie is verified; opens what verification gives.
class _VerifyBanner extends StatelessWidget {
  const _VerifyBanner({required this.onTap, this.gain});

  final VoidCallback onTap;
  final int? gain;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Material(
      color: Brand.verified.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(Radii.card),
      child: InkWell(
        borderRadius: BorderRadius.circular(Radii.card),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(Gaps.md),
          child: Row(
            children: [
              const Icon(Icons.verified_outlined, color: Brand.verified, size: 32),
              const SizedBox(width: Gaps.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Подтвердите анкету', style: theme.textTheme.titleSmall),
                    const SizedBox(height: 2),
                    Text('Селфи с жестом откроет все возможности приложения',
                        style: theme.textTheme.bodyMedium),
                  ],
                ),
              ),
              if (gain != null) _GainText(gain!),
              const Icon(Icons.chevron_right_rounded),
            ],
          ),
        ),
      ),
    );
  }
}

/// A section shown as it is, with «Редактировать» below (null [onEdit]: the content edits itself).
class _Block extends StatelessWidget {
  const _Block({required this.title, required this.onEdit, this.child, this.gain});

  final String title;
  final Widget? child;
  final VoidCallback? onEdit;
  final int? gain;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.only(bottom: Gaps.md),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.md, Gaps.md, Gaps.xs),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text(title, style: theme.textTheme.titleMedium)),
                if (gain != null) _GainText(gain!),
              ],
            ),
            const SizedBox(height: Gaps.sm),
            child ??
                Text('Не заполнено',
                    style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            if (onEdit != null)
              TextButton.icon(
                onPressed: onEdit,
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: const Text('Редактировать'),
              )
            else
              const SizedBox(height: Gaps.sm),
          ],
        ),
      ),
    );
  }
}

/// My photos in a row; a tap opens the photo section.
class _PhotoStrip extends StatelessWidget {
  const _PhotoStrip({required this.profile, required this.photoUrl, required this.onOpen, this.gain});

  final MyProfile profile;

  /// Completeness the photos would add, shown before «Изменить».
  final int? gain;
  final String Function(String url) photoUrl;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(child: _Section('Фото · ${profile.photos.length} из ${MyProfile.maxPhotos}')),
            if (gain != null)
              Text('+$gain%', style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.primary)),
            TextButton(onPressed: onOpen, child: const Text('Изменить')),
          ],
        ),
        SizedBox(
          height: 120,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: profile.photos.length + 1,
            separatorBuilder: (_, __) => const SizedBox(width: Gaps.sm),
            itemBuilder: (context, i) {
              if (i == profile.photos.length) {
                return SizedBox(
                  width: 90,
                  child: Material(
                    color: theme.colorScheme.surfaceContainer,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
                    child: InkWell(
                      customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
                      onTap: onOpen,
                      child: Icon(Icons.add_a_photo_outlined, color: theme.colorScheme.onSurfaceVariant),
                    ),
                  ),
                );
              }
              final photo = profile.photos[i];
              return GestureDetector(
                onTap: onOpen,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(Radii.md),
                  child: SizedBox(
                    width: 90,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        NetworkPhoto(url: photoUrl(photo.url)),
                        if (photo.underReview)
                          Positioned(
                            left: 0,
                            right: 0,
                            bottom: 0,
                            child: Container(
                              color: Colors.black54,
                              padding: const EdgeInsets.symmetric(vertical: 3),
                              child: const Text('На проверке',
                                  textAlign: TextAlign.center, style: TextStyle(color: Colors.white, fontSize: 11)),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section(this.title);

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: Gaps.sm + 2),
        child: Text(title, style: Theme.of(context).textTheme.titleMedium),
      );
}

/// My photo with a completeness ring, the publication state and what is left to do.
class _Header extends StatelessWidget {
  const _Header({
    required this.profile,
    required this.photoUrl,
    required this.missing,
    required this.onPublish,
    required this.onPreview,
    required this.onRefill,
  });

  final MyProfile profile;
  final String Function(String url) photoUrl;
  final List<String> missing;

  /// Publishing is the header's one job while the profile is not in the feed; hiding is in the settings.
  final VoidCallback? onPublish;
  final VoidCallback onPreview;
  final VoidCallback onRefill;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final photo = profile.photos.isEmpty ? null : profile.photos.first;
    final percent = profile.completenessPercent;
    final (label, color) = profile.isPublished
        ? ('Опубликована', const Color(0xFF16A34A))
        : profile.publicationStatus == 'HIDDEN'
            ? ('Скрыта', scheme.onSurfaceVariant)
            : ('Черновик', Brand.gold);
    return Container(
      padding: const EdgeInsets.all(Gaps.md + 4),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(Radii.card),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              SizedBox(
                width: 84,
                height: 84,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    CircularProgressIndicator(
                      value: percent / 100,
                      strokeWidth: 4,
                      strokeCap: StrokeCap.round,
                      backgroundColor: scheme.outlineVariant,
                    ),
                    Padding(
                      padding: const EdgeInsets.all(7),
                      child: ClipOval(
                        child: NetworkPhoto(url: photo == null ? null : photoUrl(photo.url)),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: Gaps.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      [
                        (profile.displayName ?? '').isNotEmpty ? profile.displayName! : 'Без имени',
                        if (profile.age != null) '${profile.age}',
                      ].join(', '),
                      style: theme.textTheme.titleLarge,
                    ),
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: Gaps.sm,
                      runSpacing: 4,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                          decoration: BoxDecoration(
                            color: color.withValues(alpha: 0.14),
                            borderRadius: BorderRadius.circular(100),
                          ),
                          child: Text(label,
                              style: theme.textTheme.labelMedium
                                  ?.copyWith(color: color, fontWeight: FontWeight.w700)),
                        ),
                        Text('Заполнено на $percent%',
                            style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (missing.isNotEmpty) ...[
            const SizedBox(height: Gaps.md),
            Text('Для публикации нужно: ${missing.join(', ')}',
                style: theme.textTheme.bodyMedium?.copyWith(color: scheme.error)),
          ],
          if (!profile.isPublished) ...[
            const SizedBox(height: Gaps.md),
            SizedBox(
              width: double.infinity,
              child: FilledButton(onPressed: onPublish, child: const Text('Опубликовать')),
            ),
          ],
          const SizedBox(height: Gaps.sm),
          Row(
            children: [
              TextButton.icon(
                onPressed: onPreview,
                icon: const Icon(Icons.visibility_outlined, size: 20),
                label: const Text('Как видят'),
              ),
              const Spacer(),
              TextButton.icon(
                onPressed: onRefill,
                icon: const Icon(Icons.restart_alt_rounded, size: 20),
                label: const Text('Заново'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// «+5%»: what filling this section adds to completeness.
class _GainText extends StatelessWidget {
  const _GainText(this.percent);

  final int percent;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(left: Gaps.sm),
      child: Text('+$percent%', style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.primary)),
    );
  }
}
