import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/app_theme.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/auth.dart';
import 'otp_screen.dart';
import 'session_controller.dart';

/// Step 1 of login: enter the phone number and request an OTP.
class PhoneScreen extends StatefulWidget {
  const PhoneScreen({super.key, required this.session});

  final SessionController session;

  @override
  State<PhoneScreen> createState() => _PhoneScreenState();
}

class _PhoneScreenState extends State<PhoneScreen> {
  final TextEditingController _phoneController = TextEditingController();
  bool _submitting = false;
  String? _error;

  @override
  void dispose() {
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_submitting) return;
    final phone = _phoneController.text.trim();
    final digits = phone.replaceAll(RegExp(r'\D'), '');
    if (digits.length < 10) {
      setState(() => _error = 'Введите номер телефона полностью');
      return;
    }

    setState(() {
      _submitting = true;
      _error = null;
    });

    final OtpChallenge challenge;
    try {
      challenge = await widget.session.api.requestOtp(phone);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = describeError(e, field: 'phone');
      });
      return;
    }

    if (!mounted) return;
    setState(() => _submitting = false);
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => OtpScreen(
          session: widget.session,
          challenge: challenge,
          phone: phone,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(Gaps.lg, 56, Gaps.lg, Gaps.lg),
          children: [
            Center(
              child: Container(
                width: 88,
                height: 88,
                decoration: BoxDecoration(
                  color: Brand.rose,
                  borderRadius: BorderRadius.circular(28),
                ),
                child: const Icon(Icons.favorite_rounded, color: Colors.white, size: 44),
              ),
            ),
            const SizedBox(height: Gaps.xl),
            Text(
              'Найдите своего человека',
              textAlign: TextAlign.center,
              style: theme.textTheme.headlineMedium,
            ),
            const SizedBox(height: Gaps.sm),
            Text(
              'Введите номер телефона — пришлём код для входа.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: Gaps.xl),
            TextField(
              controller: _phoneController,
              enabled: !_submitting,
              autofocus: true,
              keyboardType: TextInputType.phone,
              textInputAction: TextInputAction.done,
              autofillHints: const [AutofillHints.telephoneNumber],
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9+()\- ]')),
              ],
              onSubmitted: (_) => _submit(),
              decoration: InputDecoration(
                labelText: 'Телефон',
                hintText: '+7 999 123-45-67',
                prefixIcon: const Icon(Icons.phone_iphone_rounded),
                errorText: _error,
                errorMaxLines: 3,
              ),
            ),
            const SizedBox(height: Gaps.md),
            FilledButton(
              onPressed: _submitting ? null : _submit,
              child: _submitting
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('Получить код'),
            ),
          ],
        ),
      ),
    );
  }
}
