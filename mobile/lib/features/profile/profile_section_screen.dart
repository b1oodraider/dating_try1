import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/models/my_profile.dart';
import '../../core/theme/app_theme.dart';
import 'profile_saver.dart';
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

class _ProfileSectionScreenState extends State<ProfileSectionScreen> {
  late final ProfileSaver _saver = ProfileSaver(widget.api, widget.profile,
      onError: (message) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message))));

  @override
  void dispose() {
    _saver.dispose();
    super.dispose();
  }

  Future<void> _close() async {
    await _saver.flush();
    if (mounted) Navigator.of(context).pop(_saver.profile);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _close();
      },
      child: ListenableBuilder(
        listenable: _saver,
        builder: (context, _) => Scaffold(
          appBar: AppBar(
            title: Text(widget.step.title),
            actions: [
              Center(
                child: Padding(
                  padding: const EdgeInsets.only(right: Gaps.md),
                  child: Text(_saver.statusText,
                      style: theme.textTheme.labelMedium?.copyWith(
                          color: _saver.state == SaveState.failed
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
                profile: _saver.profile,
                onChanged: _saver.onChanged,
                onProfile: _saver.replace,
                errors: _saver.errors,
              )),
            ],
          ),
        ),
      ),
    );
  }
}
