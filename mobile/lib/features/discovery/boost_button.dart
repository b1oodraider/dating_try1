import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/boost.dart';
import '../../core/theme/app_theme.dart';

/// Boost in the feed's app bar: a countdown while active, otherwise a rocket with the number of boosts left.
/// Tapping it opens a sheet to start a boost or to read how to get one.
class BoostButton extends StatefulWidget {
  const BoostButton({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<BoostButton> createState() => _BoostButtonState();
}

class _BoostButtonState extends State<BoostButton> {
  BoostStatus? _status;
  DateTime _receivedAt = DateTime.now();
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      _show(await widget.api.getBoost());
    } catch (e) {
      debugPrint('Failed to load boost: $e'); // the button just stays plain
    }
  }

  void _show(BoostStatus status) {
    if (!mounted) return;
    setState(() {
      _status = status;
      _receivedAt = DateTime.now();
    });
    _tick?.cancel();
    if (status.active) {
      _tick = Timer.periodic(const Duration(seconds: 1), (_) {
        if (!mounted) return;
        if (_left == Duration.zero) {
          _tick?.cancel();
          _load();
        } else {
          setState(() {});
        }
      });
    }
  }

  Duration get _left => _status?.leftAt(_receivedAt) ?? Duration.zero;

  static String _clock(Duration d) =>
      '${d.inMinutes}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';

  Future<void> _openSheet() async {
    final started = await showModalBottomSheet<BoostStatus>(
      context: context,
      showDragHandle: true,
      builder: (_) => _BoostSheet(api: widget.api, status: _status, left: _left),
    );
    if (started != null) {
      _show(started);
    } else {
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    if (status != null && status.active) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: Gaps.xs),
        child: ActionChip(
          avatar: const Icon(Icons.rocket_launch_rounded, size: 18, color: Brand.gold),
          label: Text(_clock(_left)),
          tooltip: 'Буст идёт',
          onPressed: _openSheet,
        ),
      );
    }
    final available = status?.available ?? 0;
    return IconButton(
      tooltip: 'Буст',
      onPressed: _openSheet,
      icon: Badge(
        isLabelVisible: available > 0,
        label: Text('$available'),
        child: const Icon(Icons.rocket_launch_rounded, color: Brand.gold),
      ),
    );
  }
}

class _BoostSheet extends StatefulWidget {
  const _BoostSheet({required this.api, required this.status, required this.left});

  final DatingApiClient api;
  final BoostStatus? status;
  final Duration left;

  @override
  State<_BoostSheet> createState() => _BoostSheetState();
}

class _BoostSheetState extends State<_BoostSheet> {
  // One key per sheet: a retry after a lost response does not spend a second boost.
  final String _key = '${DateTime.now().microsecondsSinceEpoch}-${math.Random().nextInt(1 << 32)}';
  bool _starting = false;
  String? _error;

  Future<void> _start() async {
    setState(() {
      _starting = true;
      _error = null;
    });
    try {
      final status = await widget.api.activateBoost(_key);
      if (mounted) Navigator.of(context).pop(status);
    } catch (e) {
      if (!mounted) return;
      if (e is ApiException && e.isUnauthorized) return;
      setState(() {
        _starting = false;
        _error = e is ApiException && e.code == 'PROFILE_NOT_PUBLISHED'
            ? 'Сначала опубликуйте анкету'
            : describeError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = widget.status;
    final minutes = status?.durationMinutes ?? 30;
    final available = status?.available ?? 0;
    final active = status?.active ?? false;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(Gaps.lg, 0, Gaps.lg, Gaps.lg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Icon(Icons.rocket_launch_rounded, size: 48, color: Brand.gold),
            const SizedBox(height: Gaps.sm),
            Text('Буст', textAlign: TextAlign.center, style: theme.textTheme.titleLarge),
            const SizedBox(height: Gaps.sm),
            Text(
              active
                  ? 'Ваша анкета чаще появляется в ленте. Осталось ${widget.left.inMinutes} мин, '
                      'показов: ${status!.impressions}.'
                  : 'На $minutes минут ваша анкета чаще появляется в ленте у тех, кому вы подходите. '
                      'Другие не видят, что анкета в бусте.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium,
            ),
            const SizedBox(height: Gaps.lg),
            if (!active && available > 0)
              FilledButton.icon(
                onPressed: _starting ? null : _start,
                icon: const Icon(Icons.rocket_launch_rounded),
                label: Text('Запустить · осталось $available'),
              )
            else if (!active)
              Text(
                'Бустов нет. Премиум даёт 1 буст в неделю, ещё их можно получить в «Удаче» и за достижения.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            if (_error != null) ...[
              const SizedBox(height: Gaps.sm),
              Text(_error!, textAlign: TextAlign.center, style: TextStyle(color: theme.colorScheme.error)),
            ],
          ],
        ),
      ),
    );
  }
}
