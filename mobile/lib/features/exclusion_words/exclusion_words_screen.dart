import 'package:flutter/material.dart';

import '../../core/api/api_exception.dart';
import '../../core/models/exclusion_words.dart';
import '../auth/session_controller.dart';
import '../premium/premium_badge.dart';
import 'exclusion_word_rules.dart';

/// Edits the caller's private list of exclusion words.
///
/// Pops with `true` after a successful save so the caller can reload the
/// discovery feed; pops with `null` when the user just goes back.
///
/// Saved words are premium (EXCLUSION_WORDS): without premium the server
/// reports `active: false` (the list is kept but not applied) and answers
/// 403 PREMIUM_REQUIRED to edits, so the screen becomes read-only.
class ExclusionWordsScreen extends StatefulWidget {
  const ExclusionWordsScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<ExclusionWordsScreen> createState() => _ExclusionWordsScreenState();
}

class _ExclusionWordsScreenState extends State<ExclusionWordsScreen> {
  final TextEditingController _inputController = TextEditingController();
  final FocusNode _inputFocus = FocusNode();

  final List<String> _words = <String>[];

  /// The list as last loaded / saved on the server.
  List<String> _savedWords = const <String>[];
  int _maxWords = ExclusionWords.defaultMaxWords;

  /// False = the feature is not available (no premium): read-only.
  bool _active = true;

  bool _loading = true;
  String? _loadError;
  bool _saving = false;
  bool _dirty = false;
  String? _inputError;
  String? _saveError;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _inputController.dispose();
    _inputFocus.dispose();
    super.dispose();
  }

  bool get _limitReached => _words.length >= _maxWords;

  Future<void> _load() async {
    try {
      final result = await widget.session.api.getExclusionWords();
      if (!mounted) return;
      setState(() {
        _words
          ..clear()
          ..addAll(result.words);
        _savedWords = result.words;
        _maxWords = result.maxWords;
        _active = result.active;
        _loading = false;
        _loadError = null;
        _dirty = false;
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

  void _retryLoad() {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    _load();
  }

  /// Adds the text from the input field. Returns false if it is invalid.
  bool _addFromInput() {
    final word = normalizeExclusionWord(_inputController.text);
    final error = validateExclusionWord(
      word,
      existing: _words,
      maxWords: _maxWords,
    );
    if (error != null) {
      setState(() => _inputError = error);
      return false;
    }
    setState(() {
      _words.add(word);
      _dirty = true;
      _inputError = null;
      _saveError = null;
    });
    _inputController.clear();
    if (!_limitReached) _inputFocus.requestFocus();
    return true;
  }

  void _remove(String word) {
    if (_saving || !_active) return;
    setState(() {
      _words.remove(word);
      _dirty = true;
      _saveError = null;
      _inputError = null;
    });
  }

  void _onAddPressed() {
    _addFromInput();
  }

  Future<void> _save() async {
    if (_saving || _loading || !_active) return;
    // A word typed but not added yet is most likely meant to be saved too.
    if (_inputController.text.trim().isNotEmpty && !_addFromInput()) return;

    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      final saved = await widget.session.api
          .updateExclusionWords(List<String>.of(_words));
      if (!mounted) return;
      setState(() {
        _words
          ..clear()
          ..addAll(saved.words);
        _savedWords = saved.words;
        _maxWords = saved.maxWords;
        _active = saved.active;
        _saving = false;
        _dirty = false;
      });
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.isUnauthorized) return;
      setState(() {
        _saving = false;
        if (e is ApiException && e.isPremiumRequired) {
          // Premium ended while the screen was open: switch to read-only
          // and show what is actually saved.
          _active = false;
          _words
            ..clear()
            ..addAll(_savedWords);
          _dirty = false;
          _inputController.clear();
        }
        _saveError = describeError(e, field: 'words');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Слова-исключения')),
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
    final saveError = _saveError;
    final inputEnabled = _active && !_saving && !_limitReached;

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        if (!_active) ...[
          _PremiumNotice(hasWords: _words.isNotEmpty),
          const SizedBox(height: 16),
        ],
        Text(
          'Список виден только вам. Анкеты, где в «О себе» или в статусе дня '
          'встречается любое из этих слов в любой форме (например, «курю» '
          'скроет и «курить», «курит»), не попадут в вашу ленту.',
          style: theme.textTheme.bodyMedium,
        ),
        const SizedBox(height: 16),
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: TextField(
                controller: _inputController,
                focusNode: _inputFocus,
                enabled: inputEnabled,
                textInputAction: TextInputAction.done,
                textCapitalization: TextCapitalization.none,
                autocorrect: false,
                onChanged: (_) {
                  if (_inputError != null) setState(() => _inputError = null);
                },
                onSubmitted: (_) => _addFromInput(),
                decoration: InputDecoration(
                  labelText: 'Новое слово',
                  hintText: 'например, курю',
                  border: const OutlineInputBorder(),
                  helperText: !_active
                      ? 'Доступно с премиумом'
                      : _limitReached
                          ? 'Достигнут лимит: удалите слово, '
                              'чтобы добавить новое'
                          : null,
                  errorText: _inputError,
                  errorMaxLines: 3,
                  helperMaxLines: 2,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: IconButton.filledTonal(
                tooltip: 'Добавить',
                icon: const Icon(Icons.add),
                onPressed: inputEnabled ? _onAddPressed : null,
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: Text('Ваши слова', style: theme.textTheme.titleSmall),
            ),
            Text(
              '${_words.length}/$_maxWords',
              style: theme.textTheme.labelLarge?.copyWith(
                color: _limitReached ? theme.colorScheme.error : null,
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_words.isEmpty)
          Text(
            'Список пуст — фильтр выключен.',
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          )
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final word in _words)
                InputChip(
                  label: Text(word),
                  isEnabled: _active && !_saving,
                  deleteButtonTooltipMessage: 'Удалить',
                  onDeleted: _active ? () => _remove(word) : null,
                ),
            ],
          ),
        if (saveError != null) ...[
          const SizedBox(height: 16),
          Text(
            saveError,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.error,
            ),
          ),
        ],
        const SizedBox(height: 24),
        FilledButton(
          onPressed: (_saving || !_active) ? null : _save,
          child: _saving
              ? const SizedBox.square(
                  dimension: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Сохранить'),
        ),
        if (_dirty && !_saving && _active) ...[
          const SizedBox(height: 8),
          Text(
            'Есть несохранённые изменения',
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ],
    );
  }
}

/// Shown when saved words are not available without premium.
class _PremiumNotice extends StatelessWidget {
  const _PremiumNotice({required this.hasWords});

  final bool hasWords;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      color: theme.colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const PremiumBadge(),
            const SizedBox(height: 8),
            Text(
              hasWords
                  ? 'Список сохранён, но не применяется без премиума. '
                      'Изменять его можно с премиумом.'
                  : 'Слова-исключения доступны с премиумом.',
              style: theme.textTheme.bodyMedium,
            ),
          ],
        ),
      ),
    );
  }
}
