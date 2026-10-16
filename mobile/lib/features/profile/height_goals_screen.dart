import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/models/goal.dart';
import '../../core/models/my_profile.dart';
import '../auth/session_controller.dart';

/// Edits the height and relationship goals of the caller's own profile
/// (`PUT /me/profile`). The height can be removed (`clearHeight: true`),
/// goals are limited to [MyProfile.maxGoals]; an empty list clears them.
///
/// Pops with `true` after a successful save.
class HeightGoalsScreen extends StatefulWidget {
  const HeightGoalsScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<HeightGoalsScreen> createState() => _HeightGoalsScreenState();
}

class _HeightGoalsScreenState extends State<HeightGoalsScreen> {
  static const int _defaultHeight = 170;

  bool _loading = true;
  String? _loadError;
  bool _saving = false;
  String? _heightError;
  String? _goalsError;
  String? _saveError;

  List<Goal> _goals = const <Goal>[];

  // As saved on the server.
  int? _savedHeight;
  Set<String> _savedGoals = <String>{};

  // Edited values. [_hasHeight] false = no height in the profile.
  bool _hasHeight = false;
  int _height = _defaultHeight;
  Set<String> _selectedGoals = <String>{};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final api = widget.session.api;
      final results = await Future.wait<Object>([
        api.getMyProfile(),
        api.getGoals(),
      ]);
      if (!mounted) return;
      setState(() {
        _apply(results[0] as MyProfile);
        _goals = results[1] as List<Goal>;
        _loading = false;
        _loadError = null;
      });
    } catch (e) {
      if (!mounted) return;
      // On 401 the SessionController already switches to the login screen.
      if (e is ApiException && e.isUnauthorized) return;
      setState(() {
        _loading = false;
        _loadError = describeError(e);
      });
    }
  }

  void _apply(MyProfile profile) {
    final height = profile.heightCm;
    _savedHeight = height;
    _hasHeight = height != null;
    _height = (height ?? _defaultHeight)
        .clamp(MyProfile.heightMin, MyProfile.heightMax);
    _savedGoals = profile.goals.map((goal) => goal.code).toSet();
    _selectedGoals = Set<String>.of(_savedGoals);
  }

  void _retryLoad() {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    _load();
  }

  bool get _goalsChanged =>
      _selectedGoals.length != _savedGoals.length ||
      !_selectedGoals.containsAll(_savedGoals);

  Future<void> _save() async {
    if (_saving) return;
    final setHeight = _hasHeight && _height != _savedHeight ? _height : null;
    final clearHeight = !_hasHeight && _savedHeight != null;
    final goals = _goalsChanged
        ? [
            for (final goal in _goals)
              if (_selectedGoals.contains(goal.code)) goal.code,
          ]
        : null;
    if (setHeight == null && !clearHeight && goals == null) {
      Navigator.of(context).pop(false);
      return;
    }

    setState(() {
      _saving = true;
      _heightError = null;
      _goalsError = null;
      _saveError = null;
    });
    try {
      final saved = await widget.session.api.updateMyProfile(
        heightCm: setHeight,
        clearHeight: clearHeight,
        goalCodes: goals,
      );
      if (!mounted) return;
      setState(() {
        _apply(saved);
        _saving = false;
      });
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.isUnauthorized) return;
      setState(() {
        _saving = false;
        if (e is ApiException) {
          _heightError = e.fieldErrors['heightCm'];
          _goalsError = e.fieldErrors['goalCodes'];
        }
        if (_heightError == null && _goalsError == null) {
          _saveError = describeError(e);
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Рост и цели')),
      body: SafeArea(child: _buildBody(context)),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }

    final loadError = _loadError;
    if (loadError != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off, size: 48),
              const SizedBox(height: 12),
              Text(loadError, textAlign: TextAlign.center),
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

    final theme = Theme.of(context);
    final errorStyle =
        theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error);
    final heightError = _heightError;
    final goalsError = _goalsError;
    final saveError = _saveError;
    final goalsFull = _selectedGoals.length >= MyProfile.maxGoals;

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Указать рост'),
          subtitle: Text(_hasHeight ? '$_height см' : 'Рост не указан'),
          value: _hasHeight,
          onChanged: _saving
              ? null
              : (value) => setState(() {
                    _hasHeight = value;
                    _heightError = null;
                  }),
        ),
        Slider(
          value: _height.toDouble(),
          min: MyProfile.heightMin.toDouble(),
          max: MyProfile.heightMax.toDouble(),
          divisions: MyProfile.heightMax - MyProfile.heightMin,
          label: '$_height см',
          onChanged: (_hasHeight && !_saving)
              ? (value) => setState(() => _height = value.round())
              : null,
        ),
        if (heightError != null) Text(heightError, style: errorStyle),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: Text('Мои цели', style: theme.textTheme.titleSmall),
            ),
            Text(
              '${_selectedGoals.length}/${MyProfile.maxGoals}',
              style: theme.textTheme.labelLarge,
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_goals.isEmpty)
          const Text('Список целей пуст')
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final goal in _goals)
                FilterChip(
                  label: Text(goal.displayLabel),
                  selected: _selectedGoals.contains(goal.code),
                  onSelected: _saving ||
                          (goalsFull && !_selectedGoals.contains(goal.code))
                      ? null
                      : (selected) => setState(() {
                            selected
                                ? _selectedGoals.add(goal.code)
                                : _selectedGoals.remove(goal.code);
                            _goalsError = null;
                          }),
                ),
            ],
          ),
        if (goalsError != null) ...[
          const SizedBox(height: 8),
          Text(goalsError, style: errorStyle),
        ],
        if (saveError != null) ...[
          const SizedBox(height: 16),
          Text(saveError, style: errorStyle),
        ],
        const SizedBox(height: 24),
        FilledButton(
          onPressed: _saving ? null : _save,
          child: _saving
              ? const SizedBox.square(
                  dimension: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Сохранить'),
        ),
      ],
    );
  }
}
