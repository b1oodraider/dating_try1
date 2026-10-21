import 'json_utils.dart';

/// An active catalog item (`GET /catalog`, contract `CatalogItem`); only what the app lists.
class CatalogItem {
  const CatalogItem({
    required this.code,
    required this.kind,
    required this.category,
    required this.title,
    this.rarity,
  });

  final String code;

  /// CONSUMABLE or COSMETIC.
  final String kind;
  final String category;
  final String title;
  final String? rarity;

  bool get isConsumable => kind == 'CONSUMABLE';

  factory CatalogItem.fromJson(Map<String, dynamic> json) => CatalogItem(
        code: asString(json['code']),
        kind: asString(json['kind']),
        category: asString(json['category']),
        title: asString(json['title']),
        rarity: asStringOrNull(json['rarity']),
      );
}
