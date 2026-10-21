import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';

const List<(String, String)> _reasons = [
  ('SPAM', 'Спам или реклама'),
  ('FAKE_PROFILE', 'Фейковая анкета'),
  ('INAPPROPRIATE_CONTENT', 'Неприемлемый контент'),
  ('HARASSMENT', 'Оскорбления или угрозы'),
  ('SCAM', 'Мошенничество, просит деньги'),
  ('UNDERAGE', 'Несовершеннолетний'),
  ('NOT_RESPONDING', 'Не отвечает на сообщения'),
  ('OTHER', 'Другое'),
];

/// Asks for a reason and sends `POST /reports`. Returns true when sent.
Future<bool> showReportSheet(
  BuildContext context, {
  required DatingApiClient api,
  required String profileId,
  required String name,
  String? messageId,
}) async {
  final sent = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    builder: (_) => _ReportSheet(
      api: api,
      profileId: profileId,
      name: name,
      messageId: messageId,
    ),
  );
  return sent ?? false;
}

class _ReportSheet extends StatefulWidget {
  const _ReportSheet({
    required this.api,
    required this.profileId,
    required this.name,
    this.messageId,
  });

  final DatingApiClient api;
  final String profileId;
  final String name;
  final String? messageId;

  @override
  State<_ReportSheet> createState() => _ReportSheetState();
}

class _ReportSheetState extends State<_ReportSheet> {
  final TextEditingController _comment = TextEditingController();
  String _reason = 'SPAM';
  bool _sending = false;
  String? _error;

  @override
  void dispose() {
    _comment.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      final note = await widget.api.report(
        profileId: widget.profileId,
        reason: _reason,
        messageId: widget.messageId,
        comment: _comment.text,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(note ?? 'Жалоба отправлена модераторам. Спасибо!'),
      ));
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _sending = false;
        _error = describeError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                widget.messageId == null
                    ? 'Пожаловаться на ${widget.name}'
                    : 'Пожаловаться на сообщение',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              RadioGroup<String>(
                groupValue: _reason,
                onChanged: (value) {
                  if (value != null) setState(() => _reason = value);
                },
                child: Column(
                  children: [
                    for (final (code, label) in _reasons)
                      RadioListTile<String>(
                        value: code,
                        title: Text(label),
                        dense: true,
                      ),
                  ],
                ),
              ),
              TextField(
                controller: _comment,
                maxLength: 1000,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: 'Комментарий (необязательно)',
                ),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    _error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              FilledButton(
                onPressed: _sending ? null : _send,
                child: Text(_sending ? 'Отправляем…' : 'Отправить жалобу'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Confirms and blocks. Returns true when blocked.
Future<bool> confirmAndBlock(
  BuildContext context, {
  required DatingApiClient api,
  required String profileId,
  required String name,
}) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text('Заблокировать $name?'),
      content: const Text(
          'Вы перестанете видеть друг друга в ленте, симпатиях и чатах.'),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(dialogContext).pop(false),
          child: const Text('Отмена'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(dialogContext).pop(true),
          child: const Text('Заблокировать'),
        ),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) return false;
  try {
    await api.block(profileId);
    if (!context.mounted) return true;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text('$name заблокирован(а)')));
    return true;
  } catch (e) {
    if (!context.mounted) return false;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(describeError(e))));
    return false;
  }
}
