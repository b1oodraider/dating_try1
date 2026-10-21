import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../core/api/api_client.dart';
import '../../core/api/api_exception.dart';
import '../../core/models/facts.dart';
import '../profile/profile_steps.dart' show Dictionaries;
import '../../core/models/public_profile.dart';
import '../safety/report_sheet.dart';
import 'profile_card.dart';

/// [hidden]: «not interested», only I stop seeing the person.
enum ProfileDetailsResult { like, superlike, pass, blocked, hidden }

/// The whole profile in a bottom sheet, with report / block and (from the
/// feed) the reaction buttons. Returns what the user chose, or null.
/// [preview]: my own profile as others see it — a banner, no actions at all.
Future<ProfileDetailsResult?> showProfileDetails(
  BuildContext context, {
  required DatingApiClient api,
  required PublicProfile profile,
  bool withReactions = false,
  bool preview = false,
}) {
  return showModalBottomSheet<ProfileDetailsResult>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (sheetContext) => DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.92,
      maxChildSize: 0.95,
      builder: (context, controller) => ListView(
        controller: controller,
        padding: const EdgeInsets.fromLTRB(Gaps.md, 0, Gaps.md, Gaps.lg),
        children: [
          if (preview)
            Container(
              margin: const EdgeInsets.only(bottom: Gaps.md),
              padding: const EdgeInsets.symmetric(
                  horizontal: Gaps.md, vertical: Gaps.sm + 4),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.secondaryContainer,
                borderRadius: BorderRadius.circular(Radii.md),
              ),
              child: Row(
                children: [
                  Icon(Icons.visibility_outlined,
                      size: 20,
                      color:
                          Theme.of(context).colorScheme.onSecondaryContainer),
                  const SizedBox(width: Gaps.sm),
                  Expanded(
                    child: Text(
                      'Так вашу анкету видят другие',
                      style: TextStyle(
                          color: Theme.of(context)
                              .colorScheme
                              .onSecondaryContainer),
                    ),
                  ),
                ],
              ),
            ),
          FutureBuilder<ProfileOptions>(
            future: Dictionaries.options(api),
            builder: (context, snapshot) => ProfileCard(
                profile: profile, photoUrl: api.resolveMediaUrl, options: snapshot.data),
          ),
          const SizedBox(height: Gaps.lg),
          if (!preview) ...[
            if (withReactions)
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => Navigator.of(sheetContext)
                          .pop(ProfileDetailsResult.pass),
                      style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 12)),
                      child: const Text('Пропустить'),
                    ),
                  ),
                  const SizedBox(width: Gaps.sm),
                  IconButton.outlined(
                    tooltip: 'Суперлайк',
                    onPressed: () => Navigator.of(sheetContext)
                        .pop(ProfileDetailsResult.superlike),
                    icon: const Icon(Icons.star_rounded),
                  ),
                  const SizedBox(width: Gaps.sm),
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
            const SizedBox(height: Gaps.sm),
            Wrap(
              alignment: WrapAlignment.center,
              children: [
                TextButton.icon(
                  onPressed: () async {
                    try {
                      await api.hide(profile.id);
                      if (sheetContext.mounted) {
                        Navigator.of(sheetContext)
                            .pop(ProfileDetailsResult.hidden);
                      }
                    } catch (e) {
                      if (!sheetContext.mounted) return;
                      ScaffoldMessenger.of(sheetContext).showSnackBar(
                          SnackBar(content: Text(describeError(e))));
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
                      Navigator.of(sheetContext)
                          .pop(ProfileDetailsResult.blocked);
                    }
                  },
                  icon: const Icon(Icons.block),
                  label: const Text('Заблокировать'),
                ),
              ],
            ),
          ],
        ],
      ),
    ),
  );
}
