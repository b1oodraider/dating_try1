import 'json_utils.dart';

/// Kinds of activity for a date point and a place (contract `DatePlanItem.activity`, `Venue.category`).
const Map<String, String> dateActivities = {
  'COFFEE': 'Кофе',
  'WALK': 'Прогулка',
  'FOOD': 'Еда',
  'CINEMA': 'Кино',
  'CULTURE': 'Выставка, театр',
  'ACTIVE': 'Активный отдых',
  'OTHER': 'Другое',
};

/// Advertising label of a sponsored place (38-ФЗ): always shown next to it.
class VenueAd {
  const VenueAd({required this.label, required this.advertiser, this.erid});

  final String label;
  final String advertiser;
  final String? erid;

  /// «Реклама · ООО «Кафе» · erid 2VtzqX».
  String get text => [label, advertiser, if (erid != null) 'erid $erid'].join(' · ');

  factory VenueAd.fromJson(Map<String, dynamic> json) => VenueAd(
        label: asString(json['label'], 'Реклама'),
        advertiser: asString(json['advertiser']),
        erid: asStringOrNull(json['erid']),
      );
}

/// A place to suggest for a date (contract `Venue`).
/// A place found by address or name (GET /places/search, OpenStreetMap for now).
class FoundPlace {
  const FoundPlace({required this.name, this.address, required this.latitude, required this.longitude});

  final String name;
  final String? address;
  final double latitude;
  final double longitude;

  factory FoundPlace.fromJson(Map<String, dynamic> json) => FoundPlace(
        name: asString(json['name']),
        address: asStringOrNull(json['address']),
        latitude: asDoubleOrNull(json['latitude']) ?? 0,
        longitude: asDoubleOrNull(json['longitude']) ?? 0,
      );
}

class Venue {
  const Venue({
    required this.id,
    required this.name,
    required this.latitude,
    required this.longitude,
    required this.category,
    this.address,
    this.description,
    this.ad,
  });

  final String id;
  final String name;
  final String? address;
  final double latitude;
  final double longitude;
  final String category;
  final String? description;
  final VenueAd? ad;

  factory Venue.fromJson(Map<String, dynamic> json) => Venue(
        id: asString(json['id']),
        name: asString(json['name']),
        address: asStringOrNull(json['address']),
        latitude: asDoubleOrNull(json['latitude']) ?? 0,
        longitude: asDoubleOrNull(json['longitude']) ?? 0,
        category: asString(json['category'], 'OTHER'),
        description: asStringOrNull(json['description']),
        ad: json['ad'] is Map<String, dynamic> ? VenueAd.fromJson(json['ad'] as Map<String, dynamic>) : null,
      );
}

/// One point of a date plan.
class DatePlanItem {
  const DatePlanItem({
    required this.placeName,
    required this.latitude,
    required this.longitude,
    required this.startsAt,
    required this.activity,
    this.address,
    this.comment,
    this.venue,
  });

  final String placeName;
  final String? address;
  final double latitude;
  final double longitude;
  final DateTime startsAt;
  final String activity;
  final String? comment;
  final Venue? venue;

  Map<String, Object?> toJson() => {
        'placeName': placeName,
        if (address != null) 'address': address,
        'latitude': latitude,
        'longitude': longitude,
        'startsAt': startsAt.toUtc().toIso8601String(),
        'activity': activity,
        if (comment != null && comment!.isNotEmpty) 'comment': comment,
        if (venue != null) 'venueId': venue!.id,
      };

  factory DatePlanItem.fromJson(Map<String, dynamic> json) => DatePlanItem(
        placeName: asString(json['placeName']),
        address: asStringOrNull(json['address']),
        latitude: asDoubleOrNull(json['latitude']) ?? 0,
        longitude: asDoubleOrNull(json['longitude']) ?? 0,
        startsAt: asDateTimeOrNull(json['startsAt']) ?? DateTime.now(),
        activity: asString(json['activity'], 'OTHER'),
        comment: asStringOrNull(json['comment']),
        venue: json['venue'] is Map<String, dynamic> ? Venue.fromJson(json['venue'] as Map<String, dynamic>) : null,
      );
}

/// A date plan in a chat (contract `DatePlan`).
class DatePlan {
  const DatePlan({
    required this.id,
    required this.matchId,
    required this.status,
    required this.version,
    required this.mine,
    required this.lastEditMine,
    required this.canRespond,
    required this.canEdit,
    required this.items,
  });

  final String id;
  final String matchId;

  /// PROPOSED, ACCEPTED, DECLINED, CANCELLED.
  final String status;
  final int version;

  /// I proposed it.
  final bool mine;
  final bool lastEditMine;

  /// I may accept or decline it.
  final bool canRespond;

  /// It is open and I may edit it (premium).
  final bool canEdit;
  final List<DatePlanItem> items;

  bool get open => status == 'PROPOSED' || status == 'ACCEPTED';

  String get statusText => switch (status) {
        'PROPOSED' => lastEditMine ? 'Ждём ответа' : 'Вам предложили встречу',
        'ACCEPTED' => 'Договорились',
        'DECLINED' => 'Отклонён',
        'CANCELLED' => 'Отменён',
        _ => status,
      };

  factory DatePlan.fromJson(Map<String, dynamic> json) => DatePlan(
        id: asString(json['id']),
        matchId: asString(json['matchId']),
        status: asString(json['status']),
        version: asInt(json['version'], 1),
        mine: asBool(json['mine']),
        lastEditMine: asBool(json['lastEditMine']),
        canRespond: asBool(json['canRespond']),
        canEdit: asBool(json['canEdit']),
        items: asList<DatePlanItem>(json['items'], DatePlanItem.fromJson),
      );
}
