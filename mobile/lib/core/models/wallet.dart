import 'json_utils.dart';

/// `GET /me/wallet` (also returned by the cosmetics and testing endpoints).
class Wallet {
  const Wallet({
    required this.premium,
    required this.premiumFeatures,
    required this.items,
    required this.equipped,
  });

  static const Wallet empty = Wallet(
    premium: PremiumStatus(active: false, until: null),
    premiumFeatures: <String>[],
    items: <WalletItem>[],
    equipped: <String, String>{},
  );

  final PremiumStatus premium;

  /// Feature codes that require premium (from the server config).
  final List<String> premiumFeatures;

  /// Owned items with quantity > 0; consumables first.
  final List<WalletItem> items;

  /// slot → item code, including private slots (CHAT_WALLPAPER).
  final Map<String, String> equipped;

  List<WalletItem> get consumables =>
      items.where((item) => item.isConsumable).toList();

  /// Everything that is not a consumable (also unknown future kinds).
  List<WalletItem> get cosmetics =>
      items.where((item) => !item.isConsumable).toList();

  bool isEquipped(WalletItem item) {
    final slot = item.equipSlot;
    return slot != null && equipped[slot] == item.code;
  }

  factory Wallet.fromJson(Map<String, dynamic> json) {
    final equipped = <String, String>{};
    final rawEquipped = json['equipped'];
    if (rawEquipped is Map) {
      rawEquipped.forEach((slot, code) {
        if (slot is String && code is String) equipped[slot] = code;
      });
    }
    return Wallet(
      premium: PremiumStatus.fromJson(asJsonMap(json['premium'])),
      premiumFeatures: asStringList(json['premiumFeatures']),
      items: asList<WalletItem>(json['items'], WalletItem.fromJson)
          .where((item) => item.code.isNotEmpty)
          .toList(),
      equipped: Map<String, String>.unmodifiable(equipped),
    );
  }
}

class PremiumStatus {
  const PremiumStatus({required this.active, required this.until});

  final bool active;

  /// End of the premium period (economy time), null when not active.
  final DateTime? until;

  factory PremiumStatus.fromJson(Map<String, dynamic> json) {
    return PremiumStatus(
      active: asBool(json['active']),
      until: asDateTimeOrNull(json['until']),
    );
  }
}

/// Contract type `WalletItem`.
class WalletItem {
  const WalletItem({
    required this.code,
    required this.kind,
    required this.category,
    required this.title,
    required this.rarity,
    required this.equipSlot,
    required this.attributes,
    required this.quantity,
    required this.periodicQuantity,
  });

  static const String kindConsumable = 'CONSUMABLE';
  static const String kindCosmetic = 'COSMETIC';

  final String code;

  /// CONSUMABLE | COSMETIC.
  final String kind;

  /// SUPERLIKE, GACHA_TICKET, BOOST, STICKER_PACK, CHAT_WALLPAPER,
  /// PROFILE_STATUS, PHOTO_FRAME, ... (open set).
  final String category;
  final String title;
  final String? rarity;

  /// Slot the cosmetic is equipped into; null if it is not equippable.
  final String? equipSlot;
  final Map<String, Object?> attributes;

  /// Total units (cosmetics: 1).
  final int quantity;

  /// Part of [quantity] from the premium allowance (spent first).
  final int periodicQuantity;

  bool get isConsumable => kind == kindConsumable;
  bool get isEquippable => equipSlot != null;

  /// Title for display; falls back to the code.
  String get displayTitle => title.isNotEmpty ? title : code;

  factory WalletItem.fromJson(Map<String, dynamic> json) {
    final equipSlot = asStringOrNull(json['equipSlot']);
    return WalletItem(
      code: asString(json['code']),
      kind: asString(json['kind']),
      category: asString(json['category']),
      title: asString(json['title']),
      rarity: asStringOrNull(json['rarity']),
      equipSlot: (equipSlot == null || equipSlot.isEmpty) ? null : equipSlot,
      attributes: asAttributes(json['attributes']),
      quantity: asInt(json['quantity']),
      periodicQuantity: asInt(json['periodicQuantity']),
    );
  }
}
