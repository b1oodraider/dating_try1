import 'package:flutter/material.dart';

import '../../core/api/api_client.dart';
import '../../core/models/my_profile.dart';
import '../../core/models/public_profile.dart';
import 'profile_details_sheet.dart';

/// What others get for [profile]: photos under review, a hidden zodiac sign
/// and a hidden premium badge are left out; no distance and no compatibility.
PublicProfile publicViewOf(MyProfile profile) => PublicProfile(
      id: profile.id,
      displayName: profile.displayName ?? '',
      age: profile.age,
      city: profile.city,
      aboutText: profile.aboutText,
      dailyStatusText: profile.dailyStatusText,
      tags: profile.tags,
      photos: profile.photos.where((photo) => !photo.underReview).toList(),
      heightCm: profile.heightCm,
      goals: profile.goals,
      premium: profile.premium && profile.showPremiumBadge,
      verified: profile.verified,
      interests: profile.interests,
      artists: profile.artists,
      zodiac: profile.showZodiac ? profile.zodiac : null,
    );

/// «Посмотреть как видят другие»: my profile in the details sheet, without actions.
Future<void> showMyProfileAsOthers(
  BuildContext context, {
  required DatingApiClient api,
  required MyProfile profile,
}) =>
    showProfileDetails(context,
        api: api, profile: publicViewOf(profile), preview: true);
