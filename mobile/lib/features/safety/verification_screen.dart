import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/social.dart';

/// Selfie verification: the server picks a gesture, the user takes a selfie
/// showing it, a moderator compares it with the profile photos by eye.
class VerificationScreen extends StatefulWidget {
  const VerificationScreen({super.key, required this.api});

  final DatingApiClient api;

  @override
  State<VerificationScreen> createState() => _VerificationScreenState();
}

class _VerificationScreenState extends State<VerificationScreen> {
  VerificationState? _state;
  bool _busy = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } catch (e) {
      if (mounted) setState(() => _error = describeError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _load() => _run(() async {
        final state = await widget.api.getVerification();
        if (mounted) setState(() => _state = state);
      });

  Future<void> _start() => _run(() async {
        final request = await widget.api.requestVerification();
        if (mounted) {
          setState(() => _state = VerificationState(
              verified: _state?.verified ?? false, current: request));
        }
      });

  Future<void> _takeSelfie(VerificationRequest request) async {
    final photo = await ImagePicker().pickImage(
      source: ImageSource.camera,
      preferredCameraDevice: CameraDevice.front,
      maxWidth: 1600,
      imageQuality: 85,
    );
    if (photo == null || !mounted) return;
    await _run(() async {
      final updated = await widget.api.submitSelfie(
          request.id, await photo.readAsBytes(), photo.name);
      if (mounted) {
        setState(() => _state = VerificationState(
            verified: _state?.verified ?? false, current: updated));
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = _state;
    final current = state?.current;
    final theme = Theme.of(context);
    final List<Widget> content;
    if (state?.verified ?? false) {
      content = [const Text('Готово: в анкете стоит значок «Проверен».')];
    } else if (current?.status == 'AWAITING_SELFIE') {
      content = [
        Text('Сделайте селфи и покажите жест:',
            style: theme.textTheme.titleMedium),
        const SizedBox(height: 8),
        Text(current!.instruction, style: theme.textTheme.headlineSmall),
        const SizedBox(height: 16),
        FilledButton.icon(
          onPressed: _busy ? null : () => _takeSelfie(current),
          icon: const Icon(Icons.camera_alt),
          label: const Text('Сделать селфи'),
        ),
      ];
    } else if (current?.status == 'PENDING') {
      content = [
        const Text('Селфи на проверке у модератора. '
            'После решения фото удаляется.'),
      ];
    } else {
      content = [
        if (current?.status == 'REJECTED')
          Text('Прошлая проверка не пройдена: '
              '${current!.rejectReason ?? 'без комментария'}'),
        const Text('Модератор сравнит селфи с фото анкеты и поставит значок '
            '«Проверен». Автоматического распознавания лиц нет.'),
        const SizedBox(height: 16),
        FilledButton(
          onPressed: _busy ? null : _start,
          child: const Text('Начать проверку'),
        ),
      ];
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Проверка селфи')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          if (_busy) const LinearProgressIndicator(),
          ...content,
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Text(_error!,
                  style: TextStyle(color: theme.colorScheme.error)),
            ),
        ],
      ),
    );
  }
}
