import 'package:flutter/material.dart';

import '../../core/models/public_profile.dart';
import '../discovery/profile_card.dart';

/// "It's a match": offers to open the new chat.
Future<void> showMatchDialog(
  BuildContext context, {
  required PublicProfile profile,
  required String Function(String url) photoUrl,
  required VoidCallback onOpenChat,
}) {
  final photo = profile.primaryPhoto;
  return showDialog<void>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('Это мэтч! 🎉', textAlign: TextAlign.center),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ClipOval(
            child: SizedBox(
              width: 120,
              height: 120,
              child: NetworkPhoto(url: photo == null ? null : photoUrl(photo.url)),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            'Вы понравились друг другу с ${profile.displayName}',
            textAlign: TextAlign.center,
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(dialogContext).pop(),
          child: const Text('Дальше'),
        ),
        FilledButton(
          onPressed: () {
            Navigator.of(dialogContext).pop();
            onOpenChat();
          },
          child: const Text('Написать'),
        ),
      ],
    ),
  );
}
