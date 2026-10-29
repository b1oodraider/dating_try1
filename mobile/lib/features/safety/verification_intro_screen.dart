import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/theme/app_theme.dart';
import 'verification_screen.dart';

/// What selfie verification opens, with «Подтвердить личность» pinned at the bottom.
/// The text scrolls above the button, so the button never covers it.
class VerificationIntroScreen extends StatelessWidget {
  const VerificationIntroScreen({super.key, required this.api});

  final DatingApiClient api;

  static const List<(IconData, String, String)> _points = [
    (Icons.verified_rounded, 'Значок «Проверен»',
        'Его видят в ленте и в чате: вам доверяют больше и отвечают охотнее.'),
    (Icons.style_outlined, 'Анкета в ленте',
        'Когда включена обязательная проверка, в ленте показывают только подтверждённые анкеты.'),
    (Icons.all_inclusive_rounded, 'Лента без лимита',
        'Без подтверждения число анкет в день может быть ограничено — после проверки лимита нет.'),
    (Icons.photo_library_outlined, 'Любые фото',
        'Фото, где не видно лица (со спины, издалека), можно добавить только после проверки.'),
  ];

  Future<void> _start(BuildContext context) async {
    final navigator = Navigator.of(context);
    await navigator.push<void>(MaterialPageRoute<void>(builder: (_) => VerificationScreen(api: api)));
    if (navigator.mounted) navigator.pop();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Подтверждение личности')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(Gaps.lg, Gaps.sm, Gaps.lg, Gaps.lg),
        children: [
          Text('Сделайте селфи с жестом, который мы покажем. Модератор сравнит его с вашими фото — обычно это '
              'занимает немного времени. Селфи никому не показывается.',
              style: theme.textTheme.bodyLarge),
          const SizedBox(height: Gaps.lg),
          for (final (icon, title, text) in _points)
            Padding(
              padding: const EdgeInsets.only(bottom: Gaps.md),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(icon, color: Brand.verified),
                  const SizedBox(width: Gaps.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(title, style: theme.textTheme.titleSmall),
                        const SizedBox(height: 2),
                        Text(text, style: theme.textTheme.bodyMedium),
                      ],
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
      // Outside the scrolling text: the last lines stay readable above the button.
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(Gaps.lg, Gaps.sm, Gaps.lg, Gaps.md),
          child: FilledButton(onPressed: () => _start(context), child: const Text('Подтвердить личность')),
        ),
      ),
    );
  }
}
