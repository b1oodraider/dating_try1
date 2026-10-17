import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/api/api_exception.dart';
import '../../core/format.dart';
import '../../core/models/account.dart';
import '../../core/models/my_profile.dart';
import '../../core/models/social.dart';
import '../../core/models/tag.dart';
import '../auth/session_controller.dart';
import '../collection/collection_screen.dart';
import '../discovery/profile_card.dart';
import '../safety/verification_screen.dart';
import '../testing/test_panel_screen.dart';
import 'height_goals_screen.dart';

const Map<String, String> _restrictionText = {
  'LIKES': 'Лайки временно недоступны',
  'MESSAGES': 'Сообщения временно недоступны',
  'HIDDEN': 'Анкета скрыта из ленты',
  'SUSPENDED': 'Аккаунт заблокирован',
  'VERIFICATION_REQUIRED': 'Анкета скрыта до проверки селфи',
};

const Map<String, String> _missingText = {
  'displayName': 'имя',
  'age': 'возраст',
  'city': 'город',
  'photos': 'фото',
};

/// My profile: photos, name / age / city / about, cards, status of the day,
/// publish or hide, completeness tips, restrictions, and the rest of the app
/// (height and goals, collection, verification, test panel, logout).
class MyProfileScreen extends StatefulWidget {
  const MyProfileScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<MyProfileScreen> createState() => _MyProfileScreenState();
}

class _MyProfileScreenState extends State<MyProfileScreen> {
  final TextEditingController _name = TextEditingController();
  final TextEditingController _age = TextEditingController();
  final TextEditingController _city = TextEditingController();
  final TextEditingController _about = TextEditingController();
  final TextEditingController _status = TextEditingController();

  MyProfile? _profile;
  Account? _account;
  List<Tag> _allTags = const <Tag>[];
  Set<String> _tagCodes = <String>{};
  List<Restriction> _restrictions = const <Restriction>[];
  bool _loading = true;
  bool _saving = false;
  bool _uploading = false;
  String? _error;
  Map<String, String> _fieldErrors = const <String, String>{};

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final c in [_name, _age, _city, _about, _status]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait<Object>([
        widget.session.api.getMyProfile(),
        widget.session.api.getTags(),
        widget.session.api.getMe(),
        widget.session.api.getRestrictions(),
      ]);
      if (!mounted) return;
      setState(() {
        _apply(results[0] as MyProfile);
        _allTags = results[1] as List<Tag>;
        _account = results[2] as Account;
        _restrictions = results[3] as List<Restriction>;
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

  void _apply(MyProfile profile) {
    _profile = profile;
    _name.text = profile.displayName ?? '';
    _age.text = profile.age?.toString() ?? '';
    _city.text = profile.city ?? '';
    _about.text = profile.aboutText ?? '';
    _status.text = profile.dailyStatusText ?? '';
    _tagCodes = profile.tags.map((t) => t.code).toSet();
  }

  Future<void> _save() async {
    final age = int.tryParse(_age.text.trim());
    setState(() {
      _saving = true;
      _fieldErrors = const <String, String>{};
    });
    try {
      final saved = await widget.session.api.updateMyProfile(
        displayName: _name.text.trim().isEmpty ? null : _name.text.trim(),
        age: age,
        city: _city.text.trim().isEmpty ? null : _city.text.trim(),
        aboutText: _about.text.trim(),
        tagCodes: _tagCodes.toList(),
        dailyStatusText: _status.text.trim(),
      );
      if (!mounted) return;
      setState(() => _apply(saved));
      _snack('Сохранено');
    } catch (e) {
      if (!mounted) return;
      setState(() {
        if (e is ApiException) _fieldErrors = e.fieldErrors;
      });
      _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _togglePublished() async {
    final profile = _profile;
    if (profile == null) return;
    await _save();
    if (!mounted || _fieldErrors.isNotEmpty) return;
    try {
      final updated = profile.isPublished
          ? await widget.session.api.hideProfile()
          : await widget.session.api.publishProfile();
      if (!mounted) return;
      setState(() => _apply(updated));
      _snack(updated.isPublished
          ? 'Анкета опубликована — её видят в ленте'
          : 'Анкета скрыта');
    } catch (e) {
      if (mounted) _snack(describeError(e));
    }
  }

  Future<void> _addPhoto() async {
    final file = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      maxWidth: 2000,
      maxHeight: 2000,
      imageQuality: 85,
    );
    if (file == null || !mounted) return;
    setState(() => _uploading = true);
    try {
      await widget.session.api.uploadPhoto(await file.readAsBytes(), file.name);
      final profile = await widget.session.api.getMyProfile();
      if (!mounted) return;
      setState(() => _profile = profile);
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _photoAction(String photoId, String action) async {
    try {
      if (action == 'primary') {
        await widget.session.api.makePhotoPrimary(photoId);
      } else {
        await widget.session.api.deletePhoto(photoId);
      }
      final profile = await widget.session.api.getMyProfile();
      if (mounted) setState(() => _profile = profile);
    } catch (e) {
      if (mounted) _snack(describeError(e));
    }
  }

  void _snack(String text) => ScaffoldMessenger.of(context)
      .showSnackBar(SnackBar(content: Text(text)));

  Future<void> _push(Widget screen) async {
    await Navigator.of(context)
        .push<void>(MaterialPageRoute<void>(builder: (_) => screen));
    if (mounted) _load();
  }

  Future<void> _confirmLogout() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Выйти из аккаунта?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Выйти'),
          ),
        ],
      ),
    );
    if (confirmed == true) await widget.session.signOut();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Моя анкета'),
        actions: [
          IconButton(
            tooltip: 'Выйти',
            icon: const Icon(Icons.logout),
            onPressed: _confirmLogout,
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final profile = _profile;
    if (profile == null) {
      return Center(
        child: TextButton(
          onPressed: _load,
          child: Text('${_error ?? 'Ошибка'}\nПовторить',
              textAlign: TextAlign.center),
        ),
      );
    }
    final theme = Theme.of(context);
    final pendingTips = profile.tips.where((t) => !t.done).toList();

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          for (final r in _restrictions)
            Card(
              color: Colors.amber.shade100,
              child: ListTile(
                leading: const Icon(Icons.shield_outlined),
                title: Text(_restrictionText[r.kind] ?? r.kind),
                subtitle: Text(r.endsAt == null
                    ? 'До решения модератора'
                    : 'До ${formatDateTime(r.endsAt!)}'),
              ),
            ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          profile.isPublished
                              ? 'Анкета опубликована'
                              : profile.publicationStatus == 'HIDDEN'
                                  ? 'Анкета скрыта'
                                  : 'Черновик',
                          style: theme.textTheme.titleMedium,
                        ),
                      ),
                      Text('${profile.completenessPercent}%'),
                    ],
                  ),
                  const SizedBox(height: 6),
                  LinearProgressIndicator(
                      value: profile.completenessPercent / 100),
                  if (profile.missingForPublish.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(
                        'Для публикации нужно: ${profile.missingForPublish.map((f) => _missingText[f] ?? f).join(', ')}',
                        style: TextStyle(color: theme.colorScheme.error),
                      ),
                    ),
                  for (final tip in pendingTips.take(3))
                    Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Text('• ${tip.text} (+${tip.weight}%)'),
                    ),
                  const SizedBox(height: 8),
                  FilledButton(
                    onPressed: _saving ? null : _togglePublished,
                    child: Text(profile.isPublished
                        ? 'Скрыть анкету'
                        : 'Опубликовать'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text('Фото (${profile.photos.length}/${MyProfile.maxPhotos})',
              style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          SizedBox(
            height: 128,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                for (final photo in profile.photos)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: PopupMenuButton<String>(
                      onSelected: (action) => _photoAction(photo.id, action),
                      itemBuilder: (_) => [
                        if (!photo.isPrimary)
                          const PopupMenuItem(
                              value: 'primary', child: Text('Сделать главным')),
                        const PopupMenuItem(
                            value: 'delete', child: Text('Удалить')),
                      ],
                      child: Stack(
                        children: [
                          ClipRRect(
                            borderRadius: BorderRadius.circular(12),
                            child: SizedBox(
                              width: 96,
                              height: 128,
                              child: NetworkPhoto(
                                  url: widget.session.api
                                      .resolveMediaUrl(photo.url)),
                            ),
                          ),
                          if (photo.isPrimary)
                            const Positioned(
                              left: 4,
                              top: 4,
                              child: Icon(Icons.star, color: Colors.amber),
                            ),
                        ],
                      ),
                    ),
                  ),
                if (profile.photos.length < MyProfile.maxPhotos)
                  SizedBox(
                    width: 96,
                    child: OutlinedButton(
                      onPressed: _uploading ? null : _addPhoto,
                      child: _uploading
                          ? const CircularProgressIndicator()
                          : const Icon(Icons.add_a_photo_outlined),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          _field(_name, 'Имя', 'displayName'),
          _field(_age, 'Возраст', 'age', number: true),
          _field(_city, 'Город', 'city'),
          _field(_about, 'О себе', 'aboutText', lines: 4, maxLength: 1000),
          _field(_status, 'Статус на сегодня (24 часа)', 'dailyStatusText',
              maxLength: 200),
          const SizedBox(height: 8),
          Text('Карточки общения (до ${MyProfile.maxTags})',
              style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final tag in _allTags)
                FilterChip(
                  label: Text(tag.label),
                  tooltip: tag.description,
                  selected: _tagCodes.contains(tag.code),
                  onSelected: (on) => setState(() {
                    if (!on) {
                      _tagCodes.remove(tag.code);
                    } else if (_tagCodes.length < MyProfile.maxTags) {
                      _tagCodes.add(tag.code);
                    }
                  }),
                ),
            ],
          ),
          const SizedBox(height: 16),
          FilledButton.tonal(
            onPressed: _saving ? null : _save,
            child: Text(_saving ? 'Сохраняем…' : 'Сохранить'),
          ),
          const Divider(height: 32),
          ListTile(
            leading: Icon(profile.verified ? Icons.verified : Icons.camera_front_outlined,
                color: profile.verified ? Colors.teal : null),
            title: Text(profile.verified
                ? 'Селфи проверено'
                : 'Пройти проверку селфи'),
            subtitle: const Text('Значок «Проверен» в анкете'),
            onTap: profile.verified
                ? null
                : () => _push(VerificationScreen(api: widget.session.api)),
          ),
          ListTile(
            leading: const Icon(Icons.straighten),
            title: const Text('Рост и цели знакомства'),
            onTap: () => _push(HeightGoalsScreen(session: widget.session)),
          ),
          ListTile(
            leading: const Icon(Icons.collections_bookmark_outlined),
            title: const Text('Коллекция и премиум'),
            onTap: () => _push(CollectionScreen(session: widget.session)),
          ),
          if (_account?.testAccount ?? false)
            ListTile(
              leading: const Icon(Icons.science_outlined),
              title: const Text('Тест-панель'),
              onTap: () => _push(TestPanelScreen(session: widget.session)),
            ),
        ],
      ),
    );
  }

  Widget _field(
    TextEditingController controller,
    String label,
    String field, {
    bool number = false,
    int lines = 1,
    int? maxLength,
  }) =>
      Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: TextField(
          controller: controller,
          keyboardType: number ? TextInputType.number : null,
          minLines: 1,
          maxLines: lines,
          maxLength: maxLength,
          decoration: InputDecoration(
            labelText: label,
            errorText: _fieldErrors[field],
            border: const OutlineInputBorder(),
          ),
        ),
      );
}
