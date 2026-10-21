import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/account.dart';
import '../../core/models/my_profile.dart';
import '../../core/models/social.dart';
import '../../core/theme/app_theme.dart';
import '../auth/session_controller.dart';
import '../collection/collection_screen.dart';
import '../discovery/profile_card.dart';
import '../discovery/profile_preview.dart';
import '../safety/verification_screen.dart';
import '../support/support_screen.dart';
import '../testing/test_panel_screen.dart';
import '../update/update_checker.dart';
import 'profile_section_screen.dart';
import 'profile_steps.dart';
import 'profile_wizard.dart';

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
  'seeking': 'кого ищете',
  'city': 'город',
  'photos': 'фото',
};

/// My profile: an overview of sections (each opens its own screen and saves as you go), visibility switches,
/// «как видят другие», «заполнить заново», and the rest of the app (verification, collection, help, updates).
class MyProfileScreen extends StatefulWidget {
  const MyProfileScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<MyProfileScreen> createState() => _MyProfileScreenState();
}

class _MyProfileScreenState extends State<MyProfileScreen> {
  MyProfile? _profile;
  Account? _account;
  List<Restriction> _restrictions = const <Restriction>[];
  bool _loading = true;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final api = widget.session.api;
      final results = await Future.wait<Object>([api.getMyProfile(), api.getMe(), api.getRestrictions()]);
      if (!mounted) return;
      setState(() {
        _profile = results[0] as MyProfile;
        _account = results[1] as Account;
        _restrictions = results[2] as List<Restriction>;
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

  void _snack(String text) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _openSection(ProfileStep step) async {
    final profile = _profile;
    if (profile == null) return;
    final updated = await Navigator.of(context).push<MyProfile>(MaterialPageRoute<MyProfile>(
      builder: (_) => ProfileSectionScreen(api: widget.session.api, step: step, profile: profile),
    ));
    if (!mounted) return;
    if (updated != null) setState(() => _profile = updated);
    _load();
  }

  Future<void> _refill() async {
    final profile = _profile;
    if (profile == null) return;
    final ok = await _confirm(
      'Заполнить анкету заново?',
      'Пройдёте все шаги с чистого листа. На каждом шаге можно оставить текущее. Фото и прежние данные не '
          'теряются.',
      'Начать',
    );
    if (!ok || !mounted) return;
    await Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
      builder: (_) => ProfileWizardScreen(api: widget.session.api, profile: profile, mode: WizardMode.refill),
    ));
    if (mounted) _load();
  }

  Future<void> _togglePublished() async {
    final profile = _profile;
    if (profile == null) return;
    setState(() => _busy = true);
    try {
      final updated = profile.isPublished
          ? await widget.session.api.hideProfile()
          : await widget.session.api.publishProfile();
      if (!mounted) return;
      setState(() => _profile = updated);
      _snack(updated.isPublished ? 'Анкета опубликована — её видят в ленте' : 'Анкета скрыта');
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Visibility switches are saved at once.
  Future<void> _setVisibility({bool? invisible, bool? showPremiumBadge, bool? showZodiac}) async {
    setState(() => _busy = true);
    try {
      final updated = await widget.session.api
          .updateMyProfile(invisible: invisible, showPremiumBadge: showPremiumBadge, showZodiac: showZodiac);
      if (mounted) setState(() => _profile = updated);
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _push(Widget screen) async {
    await Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => screen));
    if (mounted) _load();
  }

  Future<bool> _confirm(String title, String text, String action) async {
    final answer = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(title),
        content: Text(text),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.of(c).pop(true), child: Text(action)),
        ],
      ),
    );
    return answer == true;
  }

  Future<void> _logout() async {
    if (await _confirm('Выйти из аккаунта?', 'Войти снова можно по номеру телефона.', 'Выйти')) {
      await widget.session.signOut();
    }
  }

  Future<void> _deleteAccount() async {
    final ok = await _confirm('Удалить аккаунт?',
        'Анкета, фото, пары и переписки будут удалены без возможности восстановления.', 'Удалить');
    if (!ok) return;
    try {
      await widget.session.deleteAccount();
    } catch (e) {
      if (mounted) _snack(describeError(e));
    }
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
          ],
        ),
        body: _buildBody(),
      );

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final profile = _profile;
    if (profile == null) {
      return Center(
        child: TextButton(
          onPressed: _load,
          child: Text('${_error ?? 'Ошибка'}\nПовторить', textAlign: TextAlign.center),
        ),
      );
    }
    final theme = Theme.of(context);
    final api = widget.session.api;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.xl),
        children: [
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
            tips: profile.tips.where((t) => !t.done).take(2).map((t) => '${t.text} (+${t.weight}%)').toList(),
            onTogglePublished: _busy ? null : _togglePublished,
          ),
          const SizedBox(height: Gaps.md),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () => showMyProfileAsOthers(context, api: api, profile: profile),
                  icon: const Icon(Icons.visibility_outlined, size: 20),
                  label: const Text('Как видят'),
                ),
              ),
              const SizedBox(width: Gaps.sm),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _refill,
                  icon: const Icon(Icons.restart_alt_rounded, size: 20),
                  label: const Text('Заново'),
                ),
              ),
            ],
          ),
          const SizedBox(height: Gaps.lg),
          _PhotoStrip(
            profile: profile,
            photoUrl: api.resolveMediaUrl,
            onOpen: () => _openSection(profileSteps.firstWhere((s) => s.id == 'photos')),
          ),
          const SizedBox(height: Gaps.lg),
          const _Section('Анкета'),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                for (final step in profileSteps)
                  if (step.id != 'photos')
                    ListTile(
                      leading: Icon(step.icon, color: theme.colorScheme.primary),
                      title: Text(step.title),
                      subtitle: Text(
                        step.hasValue(profile) ? step.summary(profile) : 'Не заполнено',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: step.hasValue(profile) || !step.requiredToPublish
                            ? null
                            : TextStyle(color: theme.colorScheme.error),
                      ),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      onTap: () => _openSection(step),
                    ),
              ],
            ),
          ),
          const SizedBox(height: Gaps.md),
          const _Section('Видимость'),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                SwitchListTile(
                  title: const Text('Показывать знак зодиака'),
                  subtitle: Text(profile.zodiac ?? 'Укажите дату рождения'),
                  value: profile.showZodiac,
                  onChanged: _busy || profile.zodiac == null ? null : (on) => _setVisibility(showZodiac: on),
                ),
                SwitchListTile(
                  title: const Text('Невидимка'),
                  subtitle: Text(
                      profile.premium ? 'Анкету видят только те, кого вы лайкнули' : 'Доступно с премиумом'),
                  value: profile.invisible && profile.premium,
                  onChanged: profile.premium && !_busy ? (on) => _setVisibility(invisible: on) : null,
                ),
                if (profile.premium)
                  SwitchListTile(
                    title: const Text('Показывать значок премиума'),
                    value: profile.showPremiumBadge,
                    onChanged: _busy ? null : (on) => _setVisibility(showPremiumBadge: on),
                  ),
              ],
            ),
          ),
          const SizedBox(height: Gaps.md),
          const _Section('Ещё'),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                ListTile(
                  leading: Icon(profile.verified ? Icons.verified : Icons.camera_front_outlined,
                      color: profile.verified ? Brand.verified : theme.colorScheme.primary),
                  title: Text(profile.verified ? 'Селфи проверено' : 'Пройти проверку селфи'),
                  subtitle: const Text('Значок «Проверен» и показ анкеты в ленте'),
                  trailing: profile.verified ? null : const Icon(Icons.chevron_right_rounded),
                  onTap: profile.verified ? null : () => _push(VerificationScreen(api: api)),
                ),
                ListTile(
                  leading: Icon(Icons.collections_bookmark_outlined, color: theme.colorScheme.primary),
                  title: const Text('Коллекция и премиум'),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => _push(CollectionScreen(session: widget.session)),
                ),
                ListTile(
                  leading: Icon(Icons.support_agent_rounded, color: theme.colorScheme.primary),
                  title: const Text('Помощь и поддержка'),
                  subtitle: const Text('Частые вопросы и обращения'),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => _push(SupportScreen(api: api)),
                ),
                CheckForUpdatesTile(api: api),
                if (_account?.testAccount ?? false)
                  ListTile(
                    leading: Icon(Icons.science_outlined, color: theme.colorScheme.primary),
                    title: const Text('Тест-панель'),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => _push(TestPanelScreen(session: widget.session)),
                  ),
              ],
            ),
          ),
          const SizedBox(height: Gaps.md),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.logout_rounded),
                  title: const Text('Выйти'),
                  onTap: _logout,
                ),
                ListTile(
                  leading: Icon(Icons.delete_outline_rounded, color: theme.colorScheme.error),
                  title: Text('Удалить аккаунт', style: TextStyle(color: theme.colorScheme.error)),
                  onTap: _deleteAccount,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// My photos in a row; a tap opens the photo section.
class _PhotoStrip extends StatelessWidget {
  const _PhotoStrip({required this.profile, required this.photoUrl, required this.onOpen});

  final MyProfile profile;
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
                    color: theme.colorScheme.primary.withValues(alpha: 0.08),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(Radii.md),
                      side: BorderSide(color: theme.colorScheme.primary.withValues(alpha: 0.35), width: 1.5),
                    ),
                    child: InkWell(
                      customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
                      onTap: onOpen,
                      child: Icon(Icons.add_a_photo_outlined, color: theme.colorScheme.primary),
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
    required this.tips,
    required this.onTogglePublished,
  });

  final MyProfile profile;
  final String Function(String url) photoUrl;
  final List<String> missing;
  final List<String> tips;
  final VoidCallback? onTogglePublished;

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
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.6)),
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
          for (final tip in tips)
            Padding(
              padding: const EdgeInsets.only(top: Gaps.sm),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.lightbulb_outline_rounded, size: 18, color: Brand.gold),
                  const SizedBox(width: Gaps.sm),
                  Expanded(child: Text(tip, style: theme.textTheme.bodyMedium)),
                ],
              ),
            ),
          const SizedBox(height: Gaps.md),
          SizedBox(
            width: double.infinity,
            child: profile.isPublished
                ? OutlinedButton(onPressed: onTogglePublished, child: const Text('Скрыть анкету'))
                : FilledButton(onPressed: onTogglePublished, child: const Text('Опубликовать')),
          ),
        ],
      ),
    );
  }
}
