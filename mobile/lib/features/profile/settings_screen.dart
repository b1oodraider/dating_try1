import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/models/account.dart';
import '../../core/models/my_profile.dart';
import '../../core/theme/app_theme.dart';
import '../auth/session_controller.dart';
import '../premium/purchase_screen.dart';
import '../support/support_screen.dart';
import '../update/update_checker.dart';
import 'profile_steps.dart';

/// Settings: account (phone, sign-in), location, visibility, payments, language, help, updates, sign out, deletion.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.session, required this.profile, required this.account});

  final SessionController session;
  final MyProfile profile;
  final Account? account;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late MyProfile _profile = widget.profile;
  bool _busy = false;

  void _snack(String text) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  Future<void> _togglePublished() => _run(() async {
        final api = widget.session.api;
        final updated = _profile.isPublished ? await api.hideProfile() : await api.publishProfile();
        if (!mounted) return;
        setState(() => _profile = updated);
        _snack(updated.isPublished ? 'Анкета опубликована — её видят в ленте' : 'Анкета скрыта');
      });

  /// Visibility switches are saved at once.
  Future<void> _setVisibility({bool? invisible, bool? showPremiumBadge, bool? showZodiac}) => _run(() async {
        final updated = await widget.session.api
            .updateMyProfile(invisible: invisible, showPremiumBadge: showPremiumBadge, showZodiac: showZodiac);
        if (mounted) setState(() => _profile = updated);
      });

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Premium page on top; after a purchase the profile reloads with premium on.
  Future<void> _buyPremium() async {
    final bought = await openPurchase(context, api: widget.session.api, kind: PurchaseKind.premium);
    if (!bought || !mounted) return;
    final updated = await widget.session.api.getMyProfile();
    if (mounted) setState(() => _profile = updated);
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
    if (!await _confirm('Выйти из аккаунта?', 'Войти снова можно по номеру телефона.', 'Выйти')) return;
    if (mounted) Navigator.of(context).popUntil((route) => route.isFirst);
    await widget.session.signOut();
  }

  Future<void> _deleteAccount() async {
    final ok = await _confirm('Удалить аккаунт?',
        'Анкета, фото, пары и переписки будут удалены без возможности восстановления.', 'Удалить');
    if (!ok) return;
    try {
      await widget.session.deleteAccount();
      if (mounted) Navigator.of(context).popUntil((route) => route.isFirst);
    } catch (e) {
      if (mounted) _snack(describeError(e));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final api = widget.session.api;
    final profile = _profile;
    return Scaffold(
      appBar: AppBar(title: const Text('Настройки')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.xl),
        children: [
          const _Header('Аккаунт'),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.phone_outlined),
                  title: const Text('Номер телефона'),
                  subtitle: Text(widget.account?.phoneMasked ?? '—'),
                ),
                const ListTile(
                  enabled: false,
                  leading: Icon(Icons.lock_outline_rounded),
                  title: Text('Двухфакторная аутентификация'),
                  subtitle: Text('Вход по коду из SMS. Второй фактор появится позже'),
                ),
              ],
            ),
          ),
          const _Header('Местоположение'),
          Card(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: Gaps.md),
              child: LocationSwitch(
                api: api,
                shared: profile.locationShared,
                onProfile: (p) => setState(() => _profile = p),
              ),
            ),
          ),
          const _Header('Видимость'),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                if (profile.isPublished || profile.publicationStatus == 'HIDDEN')
                  SwitchListTile(
                    title: const Text('Анкета в ленте'),
                    subtitle: Text(profile.isPublished ? 'Её видят другие' : 'Скрыта: её никто не видит'),
                    value: profile.isPublished,
                    onChanged: _busy ? null : (_) => _togglePublished(),
                  ),
                SwitchListTile(
                  title: const Text('Показывать знак зодиака'),
                  subtitle: Text(profile.zodiac ?? 'Укажите дату рождения'),
                  value: profile.showZodiac,
                  onChanged: _busy || profile.zodiac == null ? null : (on) => _setVisibility(showZodiac: on),
                ),
                SwitchListTile(
                  title: const Text('Невидимка'),
                  subtitle:
                      Text(profile.premium ? 'Анкету видят только те, кого вы лайкнули' : 'Доступно с премиумом'),
                  value: profile.invisible && profile.premium,
                  onChanged: _busy
                      ? null
                      : profile.premium
                          ? (on) => _setVisibility(invisible: on)
                          : (_) => _buyPremium(),
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
          const _Header('Оплата и язык'),
          const Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                ListTile(
                  enabled: false,
                  leading: Icon(Icons.credit_card_outlined),
                  title: Text('Способы оплаты'),
                  subtitle: Text('Не привязаны: оплата пока не подключена, покупки тестовые'),
                ),
                ListTile(
                  enabled: false,
                  leading: Icon(Icons.language_rounded),
                  title: Text('Язык'),
                  subtitle: Text('Русский · другие языки появятся позже'),
                ),
              ],
            ),
          ),
          const _Header('Приложение'),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.support_agent_rounded),
                  title: const Text('Помощь и поддержка'),
                  subtitle: const Text('Частые вопросы и обращения'),
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => Navigator.of(context)
                      .push<void>(MaterialPageRoute<void>(builder: (_) => SupportScreen(api: api))),
                ),
                CheckForUpdatesTile(api: api),
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

class _Header extends StatelessWidget {
  const _Header(this.title);

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: Gaps.md, bottom: Gaps.sm),
        child: Text(title, style: Theme.of(context).textTheme.titleMedium),
      );
}
