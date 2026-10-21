import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/theme/app_theme.dart';
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
import 'city_picker.dart';
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
  'birthDate': 'дата рождения',
  'gender': 'пол',
  'seeking': 'кого ищете',
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
  final TextEditingController _birth = TextEditingController();
  final TextEditingController _about = TextEditingController();
  final TextEditingController _status = TextEditingController();

  MyProfile? _profile;
  Account? _account;
  List<Tag> _allTags = const <Tag>[];
  Set<String> _tagCodes = <String>{};
  List<Restriction> _restrictions = const <Restriction>[];
  /// The right end of the radius slider means «no radius».
  static const int _noRadius = 300;

  int? _cityId;
  String? _cityName;
  double _radius = 300;
  bool _locating = false;

  /// The right end of the age slider means «no upper limit».
  static const int _maxAgeSlider = 70;

  String? _gender;
  String? _seeking;
  RangeValues _ages = const RangeValues(18, 70);
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
    for (final c in [_name, _birth, _about, _status]) {
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
    final born = profile.birthDate;
    _birth.text = born == null
        ? ''
        : '${born.day.toString().padLeft(2, '0')}.${born.month.toString().padLeft(2, '0')}.${born.year}';
    _gender = profile.gender;
    _seeking = profile.seeking;
    _ages = RangeValues(profile.ageFrom.toDouble(),
        (profile.ageTo ?? _maxAgeSlider).toDouble());
    _cityId = profile.cityId;
    _cityName = profile.city;
    _radius = (profile.searchRadiusKm ?? _noRadius).toDouble().clamp(5, _noRadius.toDouble());
    _about.text = profile.aboutText ?? '';
    _status.text = profile.dailyStatusText ?? '';
    _tagCodes = profile.tags.map((t) => t.code).toSet();
  }

  /// `ДД.ММ.ГГГГ` → date; null when incomplete or not a real date.
  static DateTime? _parseBirth(String text) {
    final m = RegExp(r'^(\d{2})\.(\d{2})\.(\d{4})$').firstMatch(text.trim());
    if (m == null) return null;
    final d = DateTime(int.parse(m[3]!), int.parse(m[2]!), int.parse(m[1]!));
    return d.day == int.parse(m[1]!) && d.month == int.parse(m[2]!) ? d : null;
  }

  Future<void> _save() async {
    final profile = _profile;
    final birth = _parseBirth(_birth.text);
    if (_birth.text.trim().isNotEmpty && birth == null) {
      setState(() => _fieldErrors = {'birthDate': 'Формат ДД.ММ.ГГГГ'});
      return;
    }
    final locked = profile?.identityLocked ?? false;
    final noUpperLimit = _ages.end >= _maxAgeSlider;
    setState(() {
      _saving = true;
      _fieldErrors = const <String, String>{};
    });
    try {
      final saved = await widget.session.api.updateMyProfile(
        displayName: _name.text.trim().isEmpty ? null : _name.text.trim(),
        birthDate: birth,
        gender: locked ? null : _gender,
        seeking: _seeking,
        ageFrom: _ages.start.round(),
        ageTo: noUpperLimit ? null : _ages.end.round(),
        clearAgeTo: noUpperLimit,
        cityId: _cityId,
        searchRadiusKm: _radius >= _noRadius ? null : _radius.round(),
        clearSearchRadius: _radius >= _noRadius,
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
        padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.xl),
        children: [
          for (final r in _restrictions)
            Card(
              color: theme.colorScheme.errorContainer,
              child: ListTile(
                leading: Icon(Icons.shield_outlined, color: theme.colorScheme.onErrorContainer),
                title: Text(_restrictionText[r.kind] ?? r.kind),
                subtitle: Text(r.endsAt == null
                    ? 'До решения модератора'
                    : 'До ${formatDateTime(r.endsAt!)}'),
              ),
            ),
          _Header(
            profile: profile,
            photoUrl: widget.session.api.resolveMediaUrl,
            missing: profile.missingForPublish.map((f) => _missingText[f] ?? f).toList(),
            tips: pendingTips.take(2).map((t) => '${t.text} (+${t.weight}%)').toList(),
            onTogglePublished: _saving ? null : _togglePublished,
          ),
          const SizedBox(height: Gaps.lg),
          _Section('Фото · ${profile.photos.length} из ${MyProfile.maxPhotos}'),
          SizedBox(
            height: 140,
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
                            borderRadius: BorderRadius.circular(Radii.md),
                            child: SizedBox(
                              width: 105,
                              height: 140,
                              child: NetworkPhoto(
                                  url: widget.session.api
                                      .resolveMediaUrl(photo.url)),
                            ),
                          ),
                          if (photo.isPrimary)
                            const Positioned(
                              left: 4,
                              top: 4,
                              child: Icon(Icons.star_rounded, color: Brand.gold),
                            ),
                          if (photo.underReview)
                            Positioned(
                              left: 0,
                              right: 0,
                              bottom: 0,
                              child: Tooltip(
                                message: 'Лицо не совпало с подтверждёнными '
                                    'фото. Другие увидят фото после проверки',
                                child: Container(
                                  color: Colors.black54,
                                  padding: const EdgeInsets.symmetric(vertical: 4),
                                  child: const Text('На проверке',
                                      textAlign: TextAlign.center,
                                      style: TextStyle(
                                          color: Colors.white, fontSize: 12)),
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                  ),
                if (profile.photos.length < MyProfile.maxPhotos)
                  SizedBox(
                    width: 105,
                    child: Material(
                      color: theme.colorScheme.primary.withValues(alpha: 0.08),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(Radii.md),
                        side: BorderSide(
                            color: theme.colorScheme.primary.withValues(alpha: 0.35), width: 1.5),
                      ),
                      child: InkWell(
                        customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
                        onTap: _uploading ? null : _addPhoto,
                        child: Center(
                          child: _uploading
                              ? const CircularProgressIndicator()
                              : Column(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(Icons.add_a_photo_outlined, color: theme.colorScheme.primary),
                                    const SizedBox(height: 6),
                                    Text('Добавить',
                                        style: theme.textTheme.labelLarge
                                            ?.copyWith(color: theme.colorScheme.primary)),
                                  ],
                                ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: Gaps.lg),
          const _Section('Обо мне'),
          _field(_name, 'Имя', 'displayName'),
          _buildIdentity(profile, theme),
          _buildPlace(profile, theme),
          _field(_about, 'О себе', 'aboutText', lines: 4, maxLength: 1000),
          _field(_status, 'Статус на сегодня (24 часа)', 'dailyStatusText',
              maxLength: 200),
          const SizedBox(height: Gaps.sm),
          const _Section('Карточки общения · до ${MyProfile.maxTags}'),
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
          const SizedBox(height: Gaps.lg),
          FilledButton(
            onPressed: _saving ? null : _save,
            child: Text(_saving ? 'Сохраняем…' : 'Сохранить изменения'),
          ),
          const SizedBox(height: Gaps.xl),
          const _Section('Настройки'),
          Card(
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
          ListTile(
            leading: Icon(profile.verified ? Icons.verified : Icons.camera_front_outlined,
                color: profile.verified ? Brand.verified : null),
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
          ),
        ],
      ),
    );
  }

  /// City from the dictionary, the search radius and sharing an approximate location.
  Widget _buildPlace(MyProfile profile, ThemeData theme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: 'Город',
              errorText: _fieldErrors['cityId'] ?? _fieldErrors['city'],
              suffixIcon: const Icon(Icons.search),
            ),
            child: InkWell(
              onTap: () async {
                final city = await pickCity(context, widget.session.api);
                if (city != null && mounted) {
                  setState(() {
                    _cityId = city.id;
                    _cityName = city.name;
                  });
                }
              },
              child: Text(_cityName?.isNotEmpty == true ? _cityName! : 'Выбрать город'),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'Искать в радиусе: '
          '${_radius >= _noRadius ? 'без ограничения' : '${_radius.round()} км'}',
          style: theme.textTheme.titleSmall,
        ),
        Slider(
          value: _radius,
          min: 5,
          max: _noRadius.toDouble(),
          divisions: (_noRadius - 5) ~/ 5,
          label: _radius >= _noRadius ? '∞' : '${_radius.round()} км',
          onChanged: (v) => setState(() => _radius = v),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Учитывать моё местоположение'),
          subtitle: const Text('Приблизительно, до ~1 км. Без него расстояния '
              'считаются от центра города'),
          value: profile.locationShared,
          onChanged: _locating ? null : (on) => _toggleLocation(on),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Невидимка'),
          subtitle: Text(profile.premium
              ? 'Анкету видят только те, кого вы лайкнули'
              : 'Доступно с премиумом'),
          value: profile.invisible && profile.premium,
          onChanged: profile.premium && !_saving
              ? (on) => _applyPrivacy(invisible: on)
              : null,
        ),
        if (profile.premium)
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Показывать значок премиума'),
            value: profile.showPremiumBadge,
            onChanged: _saving ? null : (on) => _applyPrivacy(showPremiumBadge: on),
          ),
        const SizedBox(height: 8),
      ],
    );
  }

  /// Applied at once, apart from the rest of the form.
  Future<void> _applyPrivacy({bool? invisible, bool? showPremiumBadge}) async {
    setState(() => _saving = true);
    try {
      final updated = await widget.session.api.updateMyProfile(
          invisible: invisible, showPremiumBadge: showPremiumBadge);
      if (mounted) setState(() => _profile = updated);
    } catch (e) {
      if (mounted) _snack(describeError(e));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _toggleLocation(bool on) async {
    setState(() => _locating = true);
    try {
      final MyProfile updated;
      if (on) {
        var permission = await Geolocator.checkPermission();
        if (permission == LocationPermission.denied) {
          permission = await Geolocator.requestPermission();
        }
        if (permission == LocationPermission.denied ||
            permission == LocationPermission.deniedForever) {
          _snack('Нет доступа к местоположению — расстояния будут от центра города');
          return;
        }
        final position = await Geolocator.getCurrentPosition(
          locationSettings: const LocationSettings(accuracy: LocationAccuracy.low),
        );
        updated = await widget.session.api
            .updateLocation(position.latitude, position.longitude);
      } else {
        updated = await widget.session.api.clearLocation();
      }
      if (mounted) setState(() => _profile = updated);
    } catch (e) {
      if (mounted) _snack(e is ApiException ? e.message : 'Не удалось определить местоположение');
    } finally {
      if (mounted) setState(() => _locating = false);
    }
  }

  /// Gender, birth date (locked after the first publish), who I look for and the ages I accept.
  Widget _buildIdentity(MyProfile profile, ThemeData theme) {
    final locked = profile.identityLocked;
    final lockedHint = locked ? 'Изменить можно через поддержку' : null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Я', style: theme.textTheme.titleSmall),
        const SizedBox(height: 6),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'FEMALE', label: Text('Женщина')),
            ButtonSegment(value: 'MALE', label: Text('Мужчина')),
          ],
          selected: {if (_gender != null) _gender!},
          emptySelectionAllowed: true,
          onSelectionChanged: locked
              ? null
              : (v) => setState(() => _gender = v.isEmpty ? null : v.first),
        ),
        if (_fieldErrors['gender'] != null || lockedHint != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(_fieldErrors['gender'] ?? lockedHint!,
                style: theme.textTheme.bodySmall?.copyWith(
                    color: _fieldErrors['gender'] != null
                        ? theme.colorScheme.error
                        : null)),
          ),
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: TextField(
            controller: _birth,
            keyboardType: TextInputType.number,
            inputFormatters: [_DateMask()],
            decoration: InputDecoration(
              labelText: 'Дата рождения',
              hintText: 'ДД.ММ.ГГГГ',
              helperText: locked
                  ? 'Можно уточнить дату, но не возраст'
                  : 'Покажем только возраст${profile.age == null ? '' : ' (${profile.age})'}',
              errorText: _fieldErrors['birthDate'],
            ),
          ),
        ),
        Text('Ищу', style: theme.textTheme.titleSmall),
        const SizedBox(height: 6),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'MALE', label: Text('Мужчину')),
            ButtonSegment(value: 'FEMALE', label: Text('Женщину')),
          ],
          selected: {if (_seeking != null) _seeking!},
          emptySelectionAllowed: true,
          onSelectionChanged: (v) =>
              setState(() => _seeking = v.isEmpty ? null : v.first),
        ),
        const SizedBox(height: 12),
        Text(
          'Возраст: ${_ages.start.round()}–'
          '${_ages.end >= _maxAgeSlider ? 'без ограничения' : _ages.end.round()}',
          style: theme.textTheme.titleSmall,
        ),
        RangeSlider(
          values: _ages,
          min: 18,
          max: _maxAgeSlider.toDouble(),
          divisions: _maxAgeSlider - 18,
          labels: RangeLabels('${_ages.start.round()}',
              _ages.end >= _maxAgeSlider ? '∞' : '${_ages.end.round()}'),
          onChanged: (v) => setState(() => _ages = v),
        ),
        if (_fieldErrors['ageTo'] != null)
          Text(_fieldErrors['ageTo']!,
              style: TextStyle(color: theme.colorScheme.error)),
        const SizedBox(height: 8),
      ],
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
          ),
        ),
      );
}

/// Types digits as `ДД.ММ.ГГГГ`, adding the dots.
class _DateMask extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final digits = newValue.text.replaceAll(RegExp(r'\D'), '');
    final buffer = StringBuffer();
    for (var i = 0; i < digits.length && i < 8; i++) {
      if (i == 2 || i == 4) buffer.write('.');
      buffer.write(digits[i]);
    }
    final text = buffer.toString();
    return TextEditingValue(text: text, selection: TextSelection.collapsed(offset: text.length));
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
