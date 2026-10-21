import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/public_profile.dart';
import '../safety/report_sheet.dart';
import 'profile_card.dart';

/// [hidden]: «not interested», only I stop seeing the person.
enum ProfileDetailsResult { like, superlike, pass, blocked, hidden }

/// The whole profile in a bottom sheet, with report / block and (from the
/// feed) the reaction buttons. Returns what the user chose, or null.
Future<ProfileDetailsResult?> showProfileDetails(
  BuildContext context, {
  required DatingApiClient api,
  required PublicProfile profile,
  bool withReactions = false,
}) {
  return showModalBottomSheet<ProfileDetailsResult>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (sheetContext) => DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.9,
      maxChildSize: 0.95,
      builder: (context, controller) => ListView(
        controller: controller,
        padding: const EdgeInsets.all(12),
        children: [
          ProfileCard(
            profile: profile,
            photoUrl: profile.primaryPhoto == null
                ? null
                : api.resolveMediaUrl(profile.primaryPhoto!.url),
          ),
          for (final photo in profile.photos.where(
              (p) => p.id != profile.primaryPhoto?.id))
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: AspectRatio(
                  aspectRatio: 3 / 4,
                  child: NetworkPhoto(url: api.resolveMediaUrl(photo.url)),
                ),
              ),
            ),
          if (withReactions)
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => Navigator.of(sheetContext)
                        .pop(ProfileDetailsResult.pass),
                    icon: const Icon(Icons.close),
                    label: const Text('Пропустить'),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.outlined(
                  tooltip: 'Суперлайк',
                  onPressed: () => Navigator.of(sheetContext)
                      .pop(ProfileDetailsResult.superlike),
                  icon: const Icon(Icons.star, color: Colors.blue),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: () => Navigator.of(sheetContext)
                        .pop(ProfileDetailsResult.like),
                    icon: const Icon(Icons.favorite),
                    label: const Text('Нравится'),
                  ),
                ),
              ],
            ),
          const SizedBox(height: 8),
          Wrap(
            alignment: WrapAlignment.center,
            children: [
              TextButton.icon(
                onPressed: () async {
                  try {
                    await api.hide(profile.id);
                    if (sheetContext.mounted) {
                      Navigator.of(sheetContext).pop(ProfileDetailsResult.hidden);
                    }
                  } catch (e) {
                    if (!sheetContext.mounted) return;
                    ScaffoldMessenger.of(sheetContext)
                        .showSnackBar(SnackBar(content: Text(describeError(e))));
                  }
                },
                icon: const Icon(Icons.visibility_off_outlined),
                label: const Text('Не показывать'),
              ),
              TextButton.icon(
                onPressed: () => showReportSheet(
                  sheetContext,
                  api: api,
                  profileId: profile.id,
                  name: profile.displayName,
                ),
                icon: const Icon(Icons.flag_outlined),
                label: const Text('Пожаловаться'),
              ),
              TextButton.icon(
                onPressed: () async {
                  final blocked = await confirmAndBlock(
                    sheetContext,
                    api: api,
                    profileId: profile.id,
                    name: profile.displayName,
                  );
                  if (blocked && sheetContext.mounted) {
                    Navigator.of(sheetContext).pop(ProfileDetailsResult.blocked);
                  }
                },
                icon: const Icon(Icons.block),
                label: const Text('Заблокировать'),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}
