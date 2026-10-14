import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/api/api_exception.dart';
import '../../core/models/auth.dart';
import 'session_controller.dart';

/// Step 2 of login: enter the 6-digit code.
class OtpScreen extends StatefulWidget {
  const OtpScreen({
    super.key,
    required this.session,
    required this.challenge,
    required this.phone,
  });

  final SessionController session;
  final OtpChallenge challenge;
  final String phone;

  @override
  State<OtpScreen> createState() => _OtpScreenState();
}

class _OtpScreenState extends State<OtpScreen> {
  final TextEditingController _codeController = TextEditingController();
  bool _submitting = false;
  String? _error;

  @override
  void dispose() {
    _codeController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting) return;
    final code = _codeController.text.trim();
    if (!RegExp(r'^\d{6}$').hasMatch(code)) {
      setState(() => _error = 'Код состоит из 6 цифр');
      return;
    }

    setState(() {
      _submitting = true;
      _error = null;
    });

    try {
      final auth = await widget.session.api.verifyOtp(
        challengeId: widget.challenge.challengeId,
        code: code,
      );
      // Switches the app to discovery; DatingApp pops this route.
      await widget.session.signIn(auth.token);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = describeError(e, field: 'code');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final devCode = widget.challenge.devMockCode;
    final destination = widget.challenge.maskedPhone ?? widget.phone;

    return Scaffold(
      appBar: AppBar(title: const Text('Код подтверждения')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              'Мы отправили код на $destination',
              style: theme.textTheme.bodyLarge,
            ),
            if (devCode != null) ...[
              const SizedBox(height: 16),
              Card(
                color: theme.colorScheme.secondaryContainer,
                child: ListTile(
                  leading: const Icon(Icons.bug_report_outlined),
                  title: Text('Тестовый код: $devCode'),
                  subtitle: const Text('Сервер работает в режиме разработки'),
                  onTap: _submitting
                      ? null
                      : () => _codeController.text = devCode,
                ),
              ),
            ],
            const SizedBox(height: 24),
            TextField(
              controller: _codeController,
              enabled: !_submitting,
              autofocus: true,
              keyboardType: TextInputType.number,
              textInputAction: TextInputAction.done,
              autofillHints: const [AutofillHints.oneTimeCode],
              maxLength: 6,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              onSubmitted: (_) => _submit(),
              decoration: InputDecoration(
                labelText: 'Код из SMS',
                border: const OutlineInputBorder(),
                errorText: _error,
                errorMaxLines: 3,
              ),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _submitting ? null : _submit,
              child: _submitting
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Войти'),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: _submitting ? null : () => Navigator.of(context).pop(),
              child: const Text('Изменить номер или запросить код заново'),
            ),
          ],
        ),
      ),
    );
  }
}
