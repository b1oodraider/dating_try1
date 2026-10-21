import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/my_profile.dart';
import '../../core/theme/app_theme.dart';
import 'profile_patch.dart';
import 'profile_steps.dart';

/// One profile section; every change is saved by itself (no «Сохранить» button). Pops with the latest profile.
class ProfileSectionScreen extends StatefulWidget {
  const ProfileSectionScreen({
    super.key,
    required this.api,
    required this.step,
    required this.profile,
  });

  final DatingApiClient api;
  final ProfileStep step;
  final MyProfile profile;

  @override
  State<ProfileSectionScreen> createState() => _ProfileSectionScreenState();
}

enum _SaveState { idle, saving, saved, failed }

class _ProfileSectionScreenState extends State<ProfileSectionScreen> {
  late MyProfile _profile = widget.profile;
  ProfilePatch _pending = ProfilePatch.empty;
  Timer? _debounce;
  Future<void>? _inFlight;
  _SaveState _state = _SaveState.idle;
  Map<String, String> _errors = const <String, String>{};

  @override
  void dispose() {
    _debounce?.cancel();
    super.dispose();
  }

  void _onChanged(ProfilePatch patch) {
    _pending = _pending.merge(patch);
    _debounce?.cancel();
    // Text is typed: wait for a pause. Taps (chips, switches, sliders) are saved almost at once.
    final typing = patch.displayName != null || patch.aboutText != null || patch.dailyStatusText != null;
    _debounce = Timer(Duration(milliseconds: typing ? 800 : 250), _flush);
  }

  Future<void> _flush() async {
    if (_pending.isEmpty) return;
    await _inFlight; // one request at a time, in order
    final patch = _pending;
    _pending = ProfilePatch.empty;
    if (mounted) setState(() => _state = _SaveState.saving);
    final run = () async {
      try {
        final saved = await patch.saveWith(widget.api);
        if (!mounted) return;
        setState(() {
          _profile = saved;
          _errors = const <String, String>{};
          _state = _SaveState.saved;
        });
      } catch (e) {
        if (!mounted) return;
        setState(() {
          _errors = e is ApiException ? e.fieldErrors : const <String, String>{};
          _state = _SaveState.failed;
        });
        if (_errors.isEmpty) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(describeError(e))));
        }
      }
    }();
    _inFlight = run;
    await run;
  }

  Future<void> _close() async {
    _debounce?.cancel();
    await _flush();
    if (mounted) Navigator.of(context).pop(_profile);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = switch (_state) {
      _SaveState.saving => 'Сохраняем…',
      _SaveState.saved => 'Сохранено',
      _SaveState.failed => 'Не сохранено',
      _SaveState.idle => '',
    };
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _close();
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.step.title),
          actions: [
            Center(
              child: Padding(
                padding: const EdgeInsets.only(right: Gaps.md),
                child: Text(status,
                    style: theme.textTheme.labelMedium?.copyWith(
                        color: _state == _SaveState.failed
                            ? theme.colorScheme.error
                            : theme.colorScheme.onSurfaceVariant)),
              ),
            ),
          ],
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(Gaps.md, Gaps.sm, Gaps.md, Gaps.xl),
          children: [
            widget.step.build(StepContext(
              api: widget.api,
              profile: _profile,
              onChanged: _onChanged,
              onProfile: (p) => setState(() => _profile = p),
              errors: _errors,
            )),
          ],
        ),
      ),
    );
  }
}
