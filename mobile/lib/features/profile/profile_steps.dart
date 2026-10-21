import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/facts.dart';
import '../../core/models/goal.dart';
import '../../core/models/interest.dart';
import '../../core/models/my_profile.dart';
import '../../core/models/tag.dart';
import '../../core/theme/app_theme.dart';
import '../discovery/profile_card.dart';
import 'city_picker.dart';
import 'profile_patch.dart';

/// What an editor works with.
///
/// [blank]: start from empty values («fill in again»: old values are only offered via «оставить текущее»).
class StepContext {
  const StepContext({
    required this.api,
    required this.profile,
    required this.onChanged,
    required this.onProfile,
    this.blank = false,
    this.errors = const <String, String>{},
  });

  final DatingApiClient api;
  final MyProfile profile;
  final bool blank;
  final ValueChanged<ProfilePatch> onChanged;

  /// For actions saved at once (photos, location): the server's new profile.
  final ValueChanged<MyProfile> onProfile;
  final Map<String, String> errors;
}

/// One part of the profile: a section of the profile screen and a step of the wizard.
class ProfileStep {
  const ProfileStep({
    required this.id,
    required this.title,
    required this.question,
    required this.icon,
    required this.summary,
    required this.hasValue,
    required this.build,
    this.requiredToPublish = false,
    this.instant = false,
  });

  final String id;
  final String title;

  /// Wizard heading («Как вас зовут?»).
  final String question;
  final IconData icon;
  final String Function(MyProfile p) summary;
  final bool Function(MyProfile p) hasValue;
  final Widget Function(StepContext c) build;
  final bool requiredToPublish;

  /// Saves on its own (photos): the wizard has nothing to send on «Далее».
  final bool instant;
}

String _date(DateTime d) =>
    '${d.day.toString().padLeft(2, '0')}.${d.month.toString().padLeft(2, '0')}.${d.year}';

String _genderLabel(String? g) => switch (g) { 'MALE' => 'Мужчина', 'FEMALE' => 'Женщина', _ => '' };

String _ages(MyProfile p) => p.ageTo == null ? 'от ${p.ageFrom}' : '${p.ageFrom}–${p.ageTo}';

/// Labels of the said facts (or how many are said while the options are not loaded yet).
String _factsSummary(MyProfile p, List<String> keys) {
  final said = keys.where((k) => p.facts.values.containsKey(k)).toList();
  final options = Dictionaries.loadedOptions;
  if (options == null) return said.isEmpty ? '' : 'указано: ${said.length}';
  return _join([for (final k in said) if (options.describe(p.facts, k) case final d?) d], max: 2);
}

String _join(Iterable<String> items, {int max = 3}) {
  final list = items.toList();
  if (list.isEmpty) return '';
  final shown = list.take(max).join(', ');
  return list.length > max ? '$shown и ещё ${list.length - max}' : shown;
}

final List<ProfileStep> profileSteps = [
  ProfileStep(
    id: 'name',
    title: 'Имя',
    question: 'Как вас зовут?',
    icon: Icons.badge_outlined,
    requiredToPublish: true,
    hasValue: (p) => (p.displayName ?? '').isNotEmpty,
    summary: (p) => p.displayName ?? '',
    build: (c) => NameEditor(c),
  ),
  ProfileStep(
    id: 'identity',
    title: 'Пол и возраст',
    question: 'Пол и дата рождения',
    icon: Icons.cake_outlined,
    requiredToPublish: true,
    hasValue: (p) => p.gender != null && p.birthDate != null,
    summary: (p) => [
      _genderLabel(p.gender),
      if (p.age != null) '${p.age}',
      if (p.zodiac != null) p.zodiac!,
    ].where((s) => s.isNotEmpty).join(' · '),
    build: (c) => IdentityEditor(c),
  ),
  ProfileStep(
    id: 'seeking',
    title: 'Кого ищу',
    question: 'Кого вы ищете?',
    icon: Icons.favorite_border_rounded,
    requiredToPublish: true,
    hasValue: (p) => p.seeking != null,
    summary: (p) => p.seeking == null
        ? ''
        : '${p.seeking == 'MALE' ? 'Мужчину' : 'Женщину'}, ${_ages(p)}',
    build: (c) => SeekingEditor(c),
  ),
  ProfileStep(
    id: 'place',
    title: 'Где искать',
    question: 'Где вы ищете?',
    icon: Icons.place_outlined,
    requiredToPublish: true,
    hasValue: (p) => (p.city ?? '').isNotEmpty,
    summary: (p) => [
      if ((p.city ?? '').isNotEmpty) p.city!,
      p.searchRadiusKm == null ? 'без радиуса' : 'до ${p.searchRadiusKm} км',
    ].join(' · '),
    build: (c) => PlaceEditor(c),
  ),
  ProfileStep(
    id: 'photos',
    title: 'Фото',
    question: 'Ваши фото',
    icon: Icons.photo_library_outlined,
    requiredToPublish: true,
    instant: true,
    hasValue: (p) => p.photos.isNotEmpty,
    summary: (p) => '${p.photos.length} из ${MyProfile.maxPhotos}',
    build: (c) => PhotosEditor(c),
  ),
  ProfileStep(
    id: 'heightGoals',
    title: 'Рост и цели',
    question: 'Рост и цели знакомства',
    icon: Icons.straighten_rounded,
    hasValue: (p) => p.heightCm != null || p.goals.isNotEmpty,
    summary: (p) => [
      if (p.heightCm != null) '${p.heightCm} см',
      if (p.goals.isNotEmpty) _join(p.goals.map((g) => g.displayLabel), max: 2),
    ].join(' · '),
    build: (c) => HeightGoalsEditor(c),
  ),
  ProfileStep(
    id: 'about',
    title: 'О себе',
    question: 'Расскажите о себе',
    icon: Icons.notes_rounded,
    hasValue: (p) => (p.aboutText ?? '').isNotEmpty,
    summary: (p) => p.aboutText ?? '',
    build: (c) => AboutEditor(c),
  ),
  ProfileStep(
    id: 'interests',
    title: 'Увлечения',
    question: 'Чем вы увлекаетесь?',
    icon: Icons.interests_outlined,
    hasValue: (p) => p.interests.isNotEmpty,
    summary: (p) => _join(p.interests.map((i) => i.label)),
    build: (c) => InterestsEditor(c),
  ),
  ProfileStep(
    id: 'music',
    title: 'Музыка',
    question: 'Любимые исполнители',
    icon: Icons.music_note_outlined,
    hasValue: (p) => p.artists.isNotEmpty,
    summary: (p) => _join(p.artists.map((a) => a.name)),
    build: (c) => MusicEditor(c),
  ),
  ProfileStep(
    id: 'lifestyle',
    title: 'Образ жизни',
    question: 'Ваш образ жизни',
    icon: Icons.spa_outlined,
    hasValue: (p) => _lifestyle.any(p.facts.values.containsKey),
    summary: (p) => _factsSummary(p, _lifestyle),
    build: (c) => FactsEditor(c, facts: _lifestyle),
  ),
  ProfileStep(
    id: 'work',
    title: 'Работа и учёба',
    question: 'Работа, учёба и языки',
    icon: Icons.work_outline_rounded,
    hasValue: (p) => _work.any(p.facts.values.containsKey),
    summary: (p) => _factsSummary(p, _work),
    build: (c) => FactsEditor(c, facts: _work),
  ),
  ProfileStep(
    id: 'tags',
    title: 'Карточки общения',
    question: 'Как вам удобнее общаться?',
    icon: Icons.chat_bubble_outline_rounded,
    hasValue: (p) => p.tags.isNotEmpty,
    summary: (p) => _join(p.tags.map((t) => t.label)),
    build: (c) => TagsEditor(c),
  ),
];

const List<String> _lifestyle = ['kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'pets'];
const List<String> _work = ['profession', 'educationLevel', 'university', 'languages'];

/// Dictionaries change rarely: loaded once per app run.
abstract final class Dictionaries {
  static Future<ProfileOptions>? _options;

  /// The options once loaded (for summaries, which cannot wait).
  static ProfileOptions? loadedOptions;

  /// Tests run each case in its own fake zone: a future cached by an earlier case never completes there.
  @visibleForTesting
  static void reset() {
    _options = null;
    loadedOptions = null;
    _tags = null;
    _goals = null;
    _interests = null;
  }

  static Future<ProfileOptions> options(DatingApiClient api) =>
      _options ??= api.getProfileOptions().then((o) => loadedOptions = o).catchError((Object e) {
        _options = null;
        throw e;
      });

  static Future<List<Tag>>? _tags;
  static Future<List<Goal>>? _goals;
  static Future<List<InterestGroup>>? _interests;

  static Future<List<Tag>> tags(DatingApiClient api) =>
      _tags ??= api.getTags().catchError((Object e) {
        _tags = null;
        throw e;
      });

  static Future<List<Goal>> goals(DatingApiClient api) =>
      _goals ??= api.getGoals().catchError((Object e) {
        _goals = null;
        throw e;
      });

  static Future<List<InterestGroup>> interests(DatingApiClient api) =>
      _interests ??= api.getInterests().catchError((Object e) {
        _interests = null;
        throw e;
      });
}

/// Hint under a step: what limits apply.
class StepHint extends StatelessWidget {
  const StepHint(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: Gaps.md),
        child: Text(text,
            style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      );
}

class _FieldError extends StatelessWidget {
  const _FieldError(this.text);

  final String? text;

  @override
  Widget build(BuildContext context) => text == null
      ? const SizedBox.shrink()
      : Padding(
          padding: const EdgeInsets.only(top: Gaps.xs),
          child: Text(text!,
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
        );
}

// ----------------------------------------------------------------- name

class NameEditor extends StatefulWidget {
  const NameEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<NameEditor> createState() => _NameEditorState();
}

class _NameEditorState extends State<NameEditor> {
  late final TextEditingController _name =
      TextEditingController(text: widget.c.blank ? '' : widget.c.profile.displayName ?? '');

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => TextField(
        controller: _name,
        autofocus: widget.c.blank,
        textCapitalization: TextCapitalization.words,
        maxLength: 50,
        decoration: InputDecoration(labelText: 'Имя', errorText: widget.c.errors['displayName']),
        onChanged: (v) {
          if (v.trim().length >= 2) widget.c.onChanged(ProfilePatch(displayName: v.trim()));
        },
      );
}

// ------------------------------------------------------------- identity

class IdentityEditor extends StatefulWidget {
  const IdentityEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<IdentityEditor> createState() => _IdentityEditorState();
}

class _IdentityEditorState extends State<IdentityEditor> {
  late String? _gender = widget.c.blank ? null : widget.c.profile.gender;
  late final TextEditingController _birth = TextEditingController(
      text: widget.c.blank || widget.c.profile.birthDate == null ? '' : _date(widget.c.profile.birthDate!));
  String? _birthError;

  @override
  void dispose() {
    _birth.dispose();
    super.dispose();
  }

  static DateTime? _parse(String text) {
    final m = RegExp(r'^(\d{2})\.(\d{2})\.(\d{4})$').firstMatch(text.trim());
    if (m == null) return null;
    final d = DateTime(int.parse(m[3]!), int.parse(m[2]!), int.parse(m[1]!));
    return d.day == int.parse(m[1]!) && d.month == int.parse(m[2]!) ? d : null;
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.c.profile;
    final theme = Theme.of(context);
    if (p.identityLocked) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.lock_outline_rounded),
            title: Text([_genderLabel(p.gender), if (p.birthDate != null) _date(p.birthDate!)].join(' · ')),
            subtitle: const Text('После публикации пол и возраст меняются через поддержку'),
          ),
          if (p.zodiac != null) Text('Знак зодиака: ${p.zodiac}', style: theme.textTheme.bodyMedium),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const StepHint('Пол и возраст нельзя будет поменять после публикации без поддержки.'),
        SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'FEMALE', label: Text('Женщина')),
              ButtonSegment(value: 'MALE', label: Text('Мужчина')),
            ],
            selected: {if (_gender != null) _gender!},
            emptySelectionAllowed: true,
            onSelectionChanged: (v) {
              if (v.isEmpty) return;
              setState(() => _gender = v.first);
              widget.c.onChanged(ProfilePatch(gender: v.first));
            },
          ),
        ),
        _FieldError(widget.c.errors['gender']),
        const SizedBox(height: Gaps.lg),
        TextField(
          controller: _birth,
          keyboardType: TextInputType.number,
          inputFormatters: [DateMask()],
          decoration: InputDecoration(
            labelText: 'Дата рождения',
            hintText: 'ДД.ММ.ГГГГ',
            helperText: 'Другим покажем только возраст',
            errorText: _birthError ?? widget.c.errors['birthDate'],
          ),
          onChanged: (v) {
            if (v.length < 10) {
              setState(() => _birthError = null);
              return;
            }
            final d = _parse(v);
            setState(() => _birthError = d == null ? 'Нет такой даты' : null);
            if (d != null) widget.c.onChanged(ProfilePatch(birthDate: d));
          },
        ),
      ],
    );
  }
}

/// Types digits as `ДД.ММ.ГГГГ`, adding the dots.
class DateMask extends TextInputFormatter {
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

// -------------------------------------------------------------- seeking

class SeekingEditor extends StatefulWidget {
  const SeekingEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<SeekingEditor> createState() => _SeekingEditorState();
}

class _SeekingEditorState extends State<SeekingEditor> {
  /// The right end means «no upper limit».
  static const int _max = 70;
  late String? _seeking = widget.c.blank ? null : widget.c.profile.seeking;
  late RangeValues _ages = widget.c.blank
      ? const RangeValues(18, 70)
      : RangeValues(widget.c.profile.ageFrom.toDouble(),
          (widget.c.profile.ageTo ?? _max).clamp(18, _max).toDouble());

  void _emitAges() {
    final noLimit = _ages.end >= _max;
    widget.c.onChanged(ProfilePatch(
      ageFrom: _ages.start.round(),
      ageTo: noLimit ? null : _ages.end.round(),
      clearAgeTo: noLimit,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'MALE', label: Text('Мужчину')),
              ButtonSegment(value: 'FEMALE', label: Text('Женщину')),
            ],
            selected: {if (_seeking != null) _seeking!},
            emptySelectionAllowed: true,
            onSelectionChanged: (v) {
              if (v.isEmpty) return;
              setState(() => _seeking = v.first);
              widget.c.onChanged(ProfilePatch(seeking: v.first));
              if (widget.c.blank) _emitAges(); // the wizard saves the shown ages too
            },
          ),
        ),
        _FieldError(widget.c.errors['seeking']),
        const SizedBox(height: Gaps.lg),
        Text(
          'Возраст: ${_ages.start.round()}–${_ages.end >= _max ? 'без ограничения' : _ages.end.round()}',
          style: theme.textTheme.titleSmall,
        ),
        RangeSlider(
          values: _ages,
          min: 18,
          max: _max.toDouble(),
          divisions: _max - 18,
          labels: RangeLabels('${_ages.start.round()}', _ages.end >= _max ? '∞' : '${_ages.end.round()}'),
          onChanged: (v) => setState(() => _ages = v),
          onChangeEnd: (_) => _emitAges(),
        ),
        _FieldError(widget.c.errors['ageTo']),
      ],
    );
  }
}

// ---------------------------------------------------------------- place

class PlaceEditor extends StatefulWidget {
  const PlaceEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<PlaceEditor> createState() => _PlaceEditorState();
}

class _PlaceEditorState extends State<PlaceEditor> {
  static const int _noRadius = 300;
  late String? _city = widget.c.blank ? null : widget.c.profile.city;
  late double _radius = widget.c.blank
      ? _noRadius.toDouble()
      : (widget.c.profile.searchRadiusKm ?? _noRadius).clamp(5, _noRadius).toDouble();
  bool _locating = false;

  void _emitRadius() => widget.c.onChanged(_radius >= _noRadius
      ? const ProfilePatch(clearSearchRadius: true)
      : ProfilePatch(searchRadiusKm: _radius.round()));

  Future<void> _toggleLocation(bool on) async {
    setState(() => _locating = true);
    try {
      final MyProfile updated;
      if (on) {
        var permission = await Geolocator.checkPermission();
        if (permission == LocationPermission.denied) {
          permission = await Geolocator.requestPermission();
        }
        if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) {
          _snack('Нет доступа к местоположению — расстояния будут от центра города');
          return;
        }
        final position = await Geolocator.getCurrentPosition(
          locationSettings: const LocationSettings(accuracy: LocationAccuracy.low),
        );
        updated = await widget.c.api.updateLocation(position.latitude, position.longitude);
      } else {
        updated = await widget.c.api.clearLocation();
      }
      widget.c.onProfile(updated);
    } catch (e) {
      _snack(e is ApiException ? e.message : 'Не удалось определить местоположение');
    } finally {
      if (mounted) setState(() => _locating = false);
    }
  }

  void _snack(String text) {
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          borderRadius: BorderRadius.circular(Radii.md),
          onTap: () async {
            final city = await pickCity(context, widget.c.api);
            if (city == null || !mounted) return;
            setState(() => _city = city.name);
            widget.c.onChanged(ProfilePatch(cityId: city.id));
          },
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: 'Город',
              suffixIcon: const Icon(Icons.search_rounded),
              errorText: widget.c.errors['cityId'] ?? widget.c.errors['city'],
            ),
            child: Text((_city ?? '').isEmpty ? 'Выбрать город' : _city!),
          ),
        ),
        const SizedBox(height: Gaps.lg),
        Text('Искать в радиусе: ${_radius >= _noRadius ? 'без ограничения' : '${_radius.round()} км'}',
            style: theme.textTheme.titleSmall),
        Slider(
          value: _radius,
          min: 5,
          max: _noRadius.toDouble(),
          divisions: (_noRadius - 5) ~/ 5,
          label: _radius >= _noRadius ? '∞' : '${_radius.round()} км',
          onChanged: (v) => setState(() => _radius = v),
          onChangeEnd: (_) => _emitRadius(),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Учитывать моё местоположение'),
          subtitle: const Text('Приблизительно, до ~1 км. Без него расстояния считаются от центра города'),
          value: widget.c.profile.locationShared,
          onChanged: _locating ? null : _toggleLocation,
        ),
      ],
    );
  }
}

// --------------------------------------------------------------- photos

class PhotosEditor extends StatefulWidget {
  const PhotosEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<PhotosEditor> createState() => _PhotosEditorState();
}

class _PhotosEditorState extends State<PhotosEditor> {
  bool _busy = false;

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
      widget.c.onProfile(await widget.c.api.getMyProfile());
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(describeError(e))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Upload progress «2 из 4» while several photos go up one by one.
  String? _progress;

  Future<void> _add() async {
    final free = MyProfile.maxPhotos - widget.c.profile.photos.length;
    final picked = await ImagePicker().pickMultiImage(
        maxWidth: 2000, maxHeight: 2000, imageQuality: 85, limit: free > 1 ? free : null);
    if (picked.isEmpty || !mounted) return;
    final files = picked.take(free).toList();
    final failed = <String>[];
    setState(() => _busy = true);
    try {
      for (var i = 0; i < files.length; i++) {
        setState(() => _progress = '${i + 1} из ${files.length}');
        try {
          await widget.c.api.uploadPhoto(await files[i].readAsBytes(), files[i].name);
        } on ApiException catch (e) {
          if (e.isUnauthorized) return;
          failed.add('Фото ${i + 1}: ${describeError(e)}');
        }
      }
      widget.c.onProfile(await widget.c.api.getMyProfile());
    } catch (e) {
      failed.add(describeError(e));
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _progress = null;
        });
        final skipped = picked.length - files.length;
        final notes = [
          ...failed,
          if (skipped > 0) 'Не поместилось фото: $skipped (максимум ${MyProfile.maxPhotos})',
        ];
        if (notes.isNotEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(notes.join('\n'))));
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.c.profile;
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const StepHint('На каждом фото должны быть вы. Можно выбрать несколько сразу. Первое фото — главное; '
            'нажмите на фото, чтобы сделать его главным или удалить.'),
        GridView.count(
          crossAxisCount: 3,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: Gaps.sm,
          crossAxisSpacing: Gaps.sm,
          childAspectRatio: 3 / 4,
          children: [
            for (final photo in p.photos)
              PopupMenuButton<String>(
                enabled: !_busy,
                onSelected: (action) => _run(() => action == 'primary'
                    ? widget.c.api.makePhotoPrimary(photo.id)
                    : widget.c.api.deletePhoto(photo.id)),
                itemBuilder: (_) => [
                  if (!photo.isPrimary) const PopupMenuItem(value: 'primary', child: Text('Сделать главным')),
                  const PopupMenuItem(value: 'delete', child: Text('Удалить')),
                ],
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(Radii.md),
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      NetworkPhoto(url: widget.c.api.resolveMediaUrl(photo.url)),
                      if (photo.isPrimary)
                        const Positioned(
                            left: 6, top: 6, child: Icon(Icons.star_rounded, color: Brand.gold)),
                      if (photo.underReview)
                        Positioned(
                          left: 0,
                          right: 0,
                          bottom: 0,
                          child: Container(
                            color: Colors.black54,
                            padding: const EdgeInsets.symmetric(vertical: 3),
                            child: const Text('На проверке',
                                textAlign: TextAlign.center,
                                style: TextStyle(color: Colors.white, fontSize: 11)),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            if (p.photos.length < MyProfile.maxPhotos)
              Material(
                color: theme.colorScheme.primary.withValues(alpha: 0.08),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(Radii.md),
                  side: BorderSide(color: theme.colorScheme.primary.withValues(alpha: 0.35), width: 1.5),
                ),
                child: InkWell(
                  customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(Radii.md)),
                  onTap: _busy ? null : _add,
                  child: Center(
                    child: _busy
                        ? Column(mainAxisSize: MainAxisSize.min, children: [
                            const CircularProgressIndicator(),
                            if (_progress != null) ...[
                              const SizedBox(height: Gaps.sm),
                              Text(_progress!, style: theme.textTheme.bodySmall),
                            ],
                          ])
                        : Icon(Icons.add_photo_alternate_outlined, color: theme.colorScheme.primary),
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

// ---------------------------------------------------------- height, goals

class HeightGoalsEditor extends StatefulWidget {
  const HeightGoalsEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<HeightGoalsEditor> createState() => _HeightGoalsEditorState();
}

class _HeightGoalsEditorState extends State<HeightGoalsEditor> {
  late bool _hasHeight = !widget.c.blank && widget.c.profile.heightCm != null;
  late double _height = (widget.c.blank ? null : widget.c.profile.heightCm)?.toDouble() ?? 170;
  late final Set<String> _goals =
      widget.c.blank ? <String>{} : widget.c.profile.goals.map((g) => g.code).toSet();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(_hasHeight ? 'Рост: ${_height.round()} см' : 'Рост не указан',
                  style: theme.textTheme.titleSmall),
            ),
            Switch(
              value: _hasHeight,
              onChanged: (on) {
                setState(() => _hasHeight = on);
                widget.c.onChanged(
                    on ? ProfilePatch(heightCm: _height.round()) : const ProfilePatch(clearHeight: true));
              },
            ),
          ],
        ),
        if (_hasHeight)
          Slider(
            value: _height,
            min: MyProfile.heightMin.toDouble(),
            max: MyProfile.heightMax.toDouble(),
            divisions: MyProfile.heightMax - MyProfile.heightMin,
            label: '${_height.round()} см',
            onChanged: (v) => setState(() => _height = v),
            onChangeEnd: (v) => widget.c.onChanged(ProfilePatch(heightCm: v.round())),
          ),
        const StepHint('С премиумом другие могут искать по росту.'),
        _FieldError(widget.c.errors['heightCm']),
        const SizedBox(height: Gaps.sm),
        Text('Цели знакомства · до ${MyProfile.maxGoals}', style: theme.textTheme.titleSmall),
        const SizedBox(height: Gaps.sm),
        FutureBuilder<List<Goal>>(
          future: Dictionaries.goals(widget.c.api),
          builder: (context, snapshot) {
            final goals = snapshot.data;
            if (goals == null) {
              return snapshot.hasError
                  ? Text(describeError(snapshot.error!))
                  : const LinearProgressIndicator();
            }
            return Wrap(
              spacing: Gaps.sm,
              runSpacing: Gaps.sm,
              children: [
                for (final goal in goals)
                  FilterChip(
                    label: Text(goal.displayLabel),
                    selected: _goals.contains(goal.code),
                    onSelected: (on) {
                      if (on && _goals.length >= MyProfile.maxGoals) return;
                      setState(() => on ? _goals.add(goal.code) : _goals.remove(goal.code));
                      widget.c.onChanged(ProfilePatch(goalCodes: _goals.toList()));
                    },
                  ),
              ],
            );
          },
        ),
        _FieldError(widget.c.errors['goalCodes']),
      ],
    );
  }
}

// ---------------------------------------------------------------- about

class AboutEditor extends StatefulWidget {
  const AboutEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<AboutEditor> createState() => _AboutEditorState();
}

class _AboutEditorState extends State<AboutEditor> {
  late final TextEditingController _about =
      TextEditingController(text: widget.c.blank ? '' : widget.c.profile.aboutText ?? '');
  late final TextEditingController _status =
      TextEditingController(text: widget.c.blank ? '' : widget.c.profile.dailyStatusText ?? '');

  @override
  void dispose() {
    _about.dispose();
    _status.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Column(
        children: [
          TextField(
            controller: _about,
            minLines: 4,
            maxLines: 8,
            maxLength: 1000,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(
              labelText: 'О себе',
              hintText: 'Чем живёте, что ищете, какой идеальный выходной',
              alignLabelWithHint: true,
              errorText: widget.c.errors['aboutText'],
            ),
            onChanged: (v) => widget.c.onChanged(ProfilePatch(aboutText: v.trim())),
          ),
          const SizedBox(height: Gaps.sm),
          TextField(
            controller: _status,
            maxLength: 200,
            textCapitalization: TextCapitalization.sentences,
            decoration: InputDecoration(
              labelText: 'Статус на сегодня',
              hintText: 'Например: иду на выставку, кто со мной?',
              helperText: 'Виден 24 часа',
              errorText: widget.c.errors['dailyStatusText'],
            ),
            onChanged: (v) => widget.c.onChanged(ProfilePatch(dailyStatusText: v.trim())),
          ),
        ],
      );
}

// ------------------------------------------------- grouped chip selection

/// Chips grouped under headers, up to [max] selected.
class _GroupedChips extends StatelessWidget {
  const _GroupedChips({
    required this.groups,
    required this.selected,
    required this.max,
    required this.onToggle,
  });

  /// Header → (code, label) in order.
  final List<(String, List<(String, String)>)> groups;
  final Set<String> selected;
  final int max;
  final void Function(String code, bool on) onToggle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (header, items) in groups) ...[
          Padding(
            padding: const EdgeInsets.only(top: Gaps.md, bottom: Gaps.sm),
            child: Text(header,
                style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ),
          Wrap(
            spacing: Gaps.sm,
            runSpacing: Gaps.sm,
            children: [
              for (final (code, label) in items)
                FilterChip(
                  label: Text(label),
                  selected: selected.contains(code),
                  onSelected: (on) {
                    if (on && selected.length >= max) {
                      ScaffoldMessenger.of(context)
                        ..hideCurrentSnackBar()
                        ..showSnackBar(SnackBar(content: Text('Можно выбрать до $max')));
                      return;
                    }
                    onToggle(code, on);
                  },
                ),
            ],
          ),
        ],
      ],
    );
  }
}

// ------------------------------------------------------------ interests

class InterestsEditor extends StatefulWidget {
  const InterestsEditor(this.c, {super.key});

  final StepContext c;

  static const int max = 10;

  @override
  State<InterestsEditor> createState() => _InterestsEditorState();
}

class _InterestsEditorState extends State<InterestsEditor> {
  late final Set<String> _selected =
      widget.c.blank ? <String>{} : widget.c.profile.interests.map((i) => i.code).toSet();

  @override
  Widget build(BuildContext context) => FutureBuilder<List<InterestGroup>>(
        future: Dictionaries.interests(widget.c.api),
        builder: (context, snapshot) {
          final groups = snapshot.data;
          if (groups == null) {
            return snapshot.hasError ? Text(describeError(snapshot.error!)) : const LinearProgressIndicator();
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              StepHint('До ${InterestsEditor.max} · выбрано ${_selected.length}. Общие увлечения поднимают '
                  'анкету в ленте того, с кем они совпадают.'),
              _GroupedChips(
                groups: [
                  for (final g in groups) (g.label, [for (final i in g.items) (i.code, i.label)]),
                ],
                selected: _selected,
                max: InterestsEditor.max,
                onToggle: (code, on) {
                  setState(() => on ? _selected.add(code) : _selected.remove(code));
                  widget.c.onChanged(ProfilePatch(interestCodes: _selected.toList()));
                },
              ),
              _FieldError(widget.c.errors['interestCodes']),
            ],
          );
        },
      );
}

// ---------------------------------------------------------------- facts

/// Facts by API name: one-of chips (tap again to clear), several-of chips, free text.
class FactsEditor extends StatefulWidget {
  const FactsEditor(this.c, {super.key, required this.facts});

  final StepContext c;
  final List<String> facts;

  @override
  State<FactsEditor> createState() => _FactsEditorState();
}

class _FactsEditorState extends State<FactsEditor> {
  late final Facts _start = widget.c.blank ? const Facts() : widget.c.profile.facts;
  late final Map<String, String?> _one = {for (final k in Facts.single) k: _start.one(k)};
  late final Map<String, Set<String>> _many = {for (final k in Facts.multi) k: _start.many(k).toSet()};
  late final Map<String, TextEditingController> _text = {
    for (final k in Facts.text) k: TextEditingController(text: _start.one(k) ?? ''),
  };

  @override
  void dispose() {
    for (final c in _text.values) {
      c.dispose();
    }
    super.dispose();
  }

  void _send(String fact, Object value) => widget.c.onChanged(ProfilePatch(facts: {fact: value}));

  void _toggleMany(String fact, String code, bool on) {
    final set = _many[fact]!;
    setState(() {
      if (!on) {
        set.remove(code);
      } else if (fact == 'pets' && (code == 'NONE') != set.contains('NONE')) {
        // «Нет животных» and animals exclude each other.
        set
          ..clear()
          ..add(code);
      } else {
        set.add(code);
      }
    });
    _send(fact, set.toList());
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<ProfileOptions>(
        future: Dictionaries.options(widget.c.api),
        builder: (context, snapshot) {
          final options = snapshot.data;
          if (options == null) {
            return snapshot.hasError ? Text(describeError(snapshot.error!)) : const LinearProgressIndicator();
          }
          final theme = Theme.of(context);
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const StepHint('Всё по желанию. Указанное видно в анкете, по образу жизни можно фильтровать ленту.'),
              for (final fact in widget.facts) ...[
                if (Facts.text.contains(fact))
                  Padding(
                    padding: const EdgeInsets.only(top: Gaps.sm),
                    child: TextField(
                      controller: _text[fact],
                      maxLength: fact == 'profession' ? 80 : 120,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: InputDecoration(
                        labelText: Facts.titles[fact],
                        errorText: widget.c.errors[fact],
                      ),
                      onChanged: (v) => _send(fact, v.trim()),
                    ),
                  )
                else ...[
                  Padding(
                    padding: const EdgeInsets.only(top: Gaps.md, bottom: Gaps.sm),
                    child: Text(Facts.titles[fact]!,
                        style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                  ),
                  Wrap(
                    spacing: Gaps.sm,
                    runSpacing: Gaps.sm,
                    children: [
                      for (final o in options.of(fact))
                        if (Facts.multi.contains(fact))
                          FilterChip(
                            label: Text(o.label),
                            selected: _many[fact]!.contains(o.code),
                            onSelected: (on) => _toggleMany(fact, o.code, on),
                          )
                        else
                          ChoiceChip(
                            label: Text(o.label),
                            selected: _one[fact] == o.code,
                            onSelected: (on) {
                              setState(() => _one[fact] = on ? o.code : null);
                              _send(fact, on ? o.code : '');
                            },
                          ),
                    ],
                  ),
                  _FieldError(widget.c.errors[fact]),
                ],
              ],
            ],
          );
        },
      );
}

// ---------------------------------------------------------------- music

class MusicEditor extends StatefulWidget {
  const MusicEditor(this.c, {super.key});

  final StepContext c;

  static const int max = 5;

  @override
  State<MusicEditor> createState() => _MusicEditorState();
}

class _MusicEditorState extends State<MusicEditor> {
  late final List<Artist> _selected = widget.c.blank ? <Artist>[] : List.of(widget.c.profile.artists);
  final TextEditingController _query = TextEditingController();
  List<Artist> _found = const <Artist>[];
  bool _searching = false;
  Timer? _debounce;
  int _generation = 0;

  @override
  void dispose() {
    _debounce?.cancel();
    _query.dispose();
    super.dispose();
  }

  void _onQuery(String text) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 400), () => _search(text));
  }

  Future<void> _search(String text) async {
    final generation = ++_generation;
    if (text.trim().length < 2) {
      setState(() => _found = const <Artist>[]);
      return;
    }
    setState(() => _searching = true);
    try {
      final found = await widget.c.api.searchArtists(text.trim());
      if (!mounted || generation != _generation) return;
      setState(() => _found = found);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(describeError(e))));
    } finally {
      if (mounted && generation == _generation) setState(() => _searching = false);
    }
  }

  void _toggle(Artist artist) {
    setState(() {
      if (_selected.contains(artist)) {
        _selected.remove(artist);
      } else if (_selected.length < MusicEditor.max) {
        _selected.add(artist);
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Можно выбрать до ${MusicEditor.max} исполнителей')));
        return;
      }
    });
    widget.c.onChanged(ProfilePatch(artistIds: _selected.map((a) => a.id).toList()));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const StepHint('До ${MusicEditor.max} исполнителей. Не нашли — попробуйте написать по-другому.'),
        if (_selected.isNotEmpty) ...[
          Wrap(
            spacing: Gaps.sm,
            runSpacing: Gaps.sm,
            children: [
              for (final a in _selected)
                InputChip(
                  label: Text(a.name),
                  selected: true,
                  onDeleted: () => _toggle(a),
                  deleteIconColor: theme.colorScheme.onPrimary,
                ),
            ],
          ),
          const SizedBox(height: Gaps.md),
        ],
        TextField(
          controller: _query,
          decoration: InputDecoration(
            labelText: 'Найти исполнителя',
            prefixIcon: const Icon(Icons.search_rounded),
            suffixIcon: _searching
                ? const Padding(
                    padding: EdgeInsets.all(14),
                    child: SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)))
                : null,
            errorText: widget.c.errors['artistIds'],
          ),
          onChanged: _onQuery,
        ),
        for (final a in _found)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.music_note_rounded),
            title: Text(a.name),
            trailing: _selected.contains(a)
                ? Icon(Icons.check_circle_rounded, color: theme.colorScheme.primary)
                : const Icon(Icons.add_circle_outline_rounded),
            onTap: () => _toggle(a),
          ),
      ],
    );
  }
}

// ----------------------------------------------------------------- tags

class TagsEditor extends StatefulWidget {
  const TagsEditor(this.c, {super.key});

  final StepContext c;

  @override
  State<TagsEditor> createState() => _TagsEditorState();
}

class _TagsEditorState extends State<TagsEditor> {
  late final Set<String> _selected =
      widget.c.blank ? <String>{} : widget.c.profile.tags.map((t) => t.code).toSet();

  @override
  Widget build(BuildContext context) => FutureBuilder<List<Tag>>(
        future: Dictionaries.tags(widget.c.api),
        builder: (context, snapshot) {
          final tags = snapshot.data;
          if (tags == null) {
            return snapshot.hasError ? Text(describeError(snapshot.error!)) : const LinearProgressIndicator();
          }
          final groups = <String, List<(String, String)>>{};
          for (final t in tags) {
            groups.putIfAbsent(t.categoryLabel.isEmpty ? 'Другое' : t.categoryLabel, () => []).add((t.code, t.label));
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              StepHint('До ${MyProfile.maxTags} · выбрано ${_selected.length}. Помогают понять, совпадаете ли '
                  'вы по стилю общения.'),
              _GroupedChips(
                groups: [for (final e in groups.entries) (e.key, e.value)],
                selected: _selected,
                max: MyProfile.maxTags,
                onToggle: (code, on) {
                  setState(() => on ? _selected.add(code) : _selected.remove(code));
                  widget.c.onChanged(ProfilePatch(tagCodes: _selected.toList()));
                },
              ),
              _FieldError(widget.c.errors['tagCodes']),
            ],
          );
        },
      );
}
