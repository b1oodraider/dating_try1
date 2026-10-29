import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/my_profile.dart';
import 'profile_patch.dart';

enum SaveState { idle, saving, saved, failed }

/// Saves profile edits as they happen (no «Сохранить» button): typed text after a pause, taps almost at once,
/// one request at a time and in order. Used by a section screen and by the fields right on «Моя анкета».
class ProfileSaver extends ChangeNotifier {
  ProfileSaver(this.api, this.profile, {this.onError});

  final DatingApiClient api;

  /// The latest profile from the server.
  MyProfile profile;

  /// An error that is not about one field (network, server).
  final void Function(String message)? onError;

  SaveState state = SaveState.idle;
  Map<String, String> errors = const <String, String>{};

  ProfilePatch _pending = ProfilePatch.empty;
  Timer? _debounce;
  Future<void>? _inFlight;
  bool _disposed = false;

  String get statusText => switch (state) {
        SaveState.saving => 'Сохраняем…',
        SaveState.saved => 'Сохранено',
        SaveState.failed => 'Не сохранено',
        SaveState.idle => '',
      };

  void onChanged(ProfilePatch patch) {
    _pending = _pending.merge(patch);
    _debounce?.cancel();
    // Text is typed: wait for a pause. Taps (chips, switches, sliders) are saved almost at once.
    final typing = patch.displayName != null || patch.aboutText != null || patch.dailyStatusText != null;
    _debounce = Timer(Duration(milliseconds: typing ? 800 : 250), flush);
  }

  /// A profile saved elsewhere (photos, location).
  void replace(MyProfile updated) {
    profile = updated;
    _notify();
  }

  Future<void> flush() async {
    _debounce?.cancel();
    if (_pending.isEmpty) return;
    await _inFlight;
    final patch = _pending;
    _pending = ProfilePatch.empty;
    state = SaveState.saving;
    _notify();
    final run = () async {
      try {
        profile = await patch.saveWith(api);
        errors = const <String, String>{};
        state = SaveState.saved;
      } catch (e) {
        errors = e is ApiException ? e.fieldErrors : const <String, String>{};
        state = SaveState.failed;
        if (errors.isEmpty) onError?.call(describeError(e));
      }
      _notify();
    }();
    _inFlight = run;
    await run;
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _debounce?.cancel();
    if (!_pending.isEmpty) unawaited(_flushDetached());
    super.dispose();
  }

  /// What is still typed when the screen closes is sent anyway.
  Future<void> _flushDetached() async {
    await _inFlight;
    try {
      await _pending.saveWith(api);
    } catch (e) {
      debugPrint('Unsaved profile edit lost: $e');
    }
  }
}
