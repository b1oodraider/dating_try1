import '../../core/api/api_client.dart';
import '../../core/models/my_profile.dart';

/// A partial profile change (only the fields set are sent). Editors emit patches; the section screen saves
/// them at once, the wizard on «Далее».
class ProfilePatch {
  const ProfilePatch({
    this.displayName,
    this.birthDate,
    this.gender,
    this.seeking,
    this.ageFrom,
    this.ageTo,
    this.clearAgeTo = false,
    this.cityId,
    this.searchRadiusKm,
    this.clearSearchRadius = false,
    this.aboutText,
    this.dailyStatusText,
    this.tagCodes,
    this.heightCm,
    this.clearHeight = false,
    this.goalCodes,
    this.interestCodes,
    this.artistIds,
    this.invisible,
    this.showPremiumBadge,
    this.showZodiac,
  });

  static const ProfilePatch empty = ProfilePatch();

  final String? displayName;
  final DateTime? birthDate;
  final String? gender;
  final String? seeking;
  final int? ageFrom;
  final int? ageTo;
  final bool clearAgeTo;
  final int? cityId;
  final int? searchRadiusKm;
  final bool clearSearchRadius;
  final String? aboutText;
  final String? dailyStatusText;
  final List<String>? tagCodes;
  final int? heightCm;
  final bool clearHeight;
  final List<String>? goalCodes;
  final List<String>? interestCodes;
  final List<int>? artistIds;
  final bool? invisible;
  final bool? showPremiumBadge;
  final bool? showZodiac;

  bool get isEmpty =>
      displayName == null &&
      birthDate == null &&
      gender == null &&
      seeking == null &&
      ageFrom == null &&
      ageTo == null &&
      !clearAgeTo &&
      cityId == null &&
      searchRadiusKm == null &&
      !clearSearchRadius &&
      aboutText == null &&
      dailyStatusText == null &&
      tagCodes == null &&
      heightCm == null &&
      !clearHeight &&
      goalCodes == null &&
      interestCodes == null &&
      artistIds == null &&
      invisible == null &&
      showPremiumBadge == null &&
      showZodiac == null;

  /// [later] wins field by field.
  ProfilePatch merge(ProfilePatch later) => ProfilePatch(
        displayName: later.displayName ?? displayName,
        birthDate: later.birthDate ?? birthDate,
        gender: later.gender ?? gender,
        seeking: later.seeking ?? seeking,
        ageFrom: later.ageFrom ?? ageFrom,
        ageTo: later.clearAgeTo ? null : (later.ageTo ?? ageTo),
        clearAgeTo:
            later.ageTo != null ? false : (later.clearAgeTo || clearAgeTo),
        cityId: later.cityId ?? cityId,
        searchRadiusKm: later.clearSearchRadius
            ? null
            : (later.searchRadiusKm ?? searchRadiusKm),
        clearSearchRadius: later.searchRadiusKm != null
            ? false
            : (later.clearSearchRadius || clearSearchRadius),
        aboutText: later.aboutText ?? aboutText,
        dailyStatusText: later.dailyStatusText ?? dailyStatusText,
        tagCodes: later.tagCodes ?? tagCodes,
        heightCm: later.clearHeight ? null : (later.heightCm ?? heightCm),
        clearHeight:
            later.heightCm != null ? false : (later.clearHeight || clearHeight),
        goalCodes: later.goalCodes ?? goalCodes,
        interestCodes: later.interestCodes ?? interestCodes,
        artistIds: later.artistIds ?? artistIds,
        invisible: later.invisible ?? invisible,
        showPremiumBadge: later.showPremiumBadge ?? showPremiumBadge,
        showZodiac: later.showZodiac ?? showZodiac,
      );

  Future<MyProfile> saveWith(DatingApiClient api) => api.updateMyProfile(
        displayName: displayName,
        birthDate: birthDate,
        gender: gender,
        seeking: seeking,
        ageFrom: ageFrom,
        ageTo: ageTo,
        clearAgeTo: clearAgeTo,
        cityId: cityId,
        searchRadiusKm: searchRadiusKm,
        clearSearchRadius: clearSearchRadius,
        aboutText: aboutText,
        dailyStatusText: dailyStatusText,
        tagCodes: tagCodes,
        heightCm: heightCm,
        clearHeight: clearHeight,
        goalCodes: goalCodes,
        interestCodes: interestCodes,
        artistIds: artistIds,
        invisible: invisible,
        showPremiumBadge: showPremiumBadge,
        showZodiac: showZodiac,
      );
}
