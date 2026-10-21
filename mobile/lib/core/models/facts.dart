import 'json_utils.dart';

/// Profile facts (contract `Facts`): codes from GET /profile-options; null / empty = not said.
/// Keys of [values] are the API field names (kidsHas, smoking, pets, profession, ...).
class Facts {
  const Facts([this.values = const <String, Object>{}]);

  /// Single-choice facts, filterable ones first.
  static const List<String> single = <String>[
    'kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'educationLevel',
  ];

  /// Multi-choice facts.
  static const List<String> multi = <String>['pets', 'languages'];

  /// Free text facts.
  static const List<String> text = <String>['profession', 'university'];

  /// Facts the feed can be filtered on (PUT /me/fact-filters keys).
  static const List<String> filterable = <String>[
    'kidsHas', 'kidsPlans', 'smoking', 'alcohol', 'sport', 'pets',
  ];

  static const Map<String, String> titles = <String, String>{
    'kidsHas': 'Дети',
    'kidsPlans': 'Планы на детей',
    'smoking': 'Курение',
    'alcohol': 'Алкоголь',
    'sport': 'Спорт',
    'pets': 'Животные',
    'educationLevel': 'Образование',
    'languages': 'Языки',
    'profession': 'Профессия',
    'university': 'Вуз',
  };

  /// String for single/text facts, `List<String>` for multi ones; only said facts are kept.
  final Map<String, Object> values;

  String? one(String key) => values[key] as String?;

  List<String> many(String key) => (values[key] as List<String>?) ?? const <String>[];

  bool get isEmpty => values.isEmpty;

  factory Facts.fromJson(Object? json) {
    final map = asJsonMap(json);
    final values = <String, Object>{};
    for (final key in [...single, ...text]) {
      final v = asStringOrNull(map[key]);
      if (v != null && v.isNotEmpty) values[key] = v;
    }
    for (final key in multi) {
      final v = asStringList(map[key]);
      if (v.isNotEmpty) values[key] = v;
    }
    return Facts(values);
  }
}

class FactOption {
  const FactOption(this.code, this.label);

  final String code;
  final String label;
}

/// GET /profile-options: answers per fact, in display order.
class ProfileOptions {
  const ProfileOptions(this.byFact);

  final Map<String, List<FactOption>> byFact;

  List<FactOption> of(String fact) => byFact[fact] ?? const <FactOption>[];

  String label(String fact, String code) {
    for (final o in of(fact)) {
      if (o.code == code) return o.label;
    }
    return code;
  }

  /// Human text of one fact: labels joined, or the free text itself.
  String? describe(Facts facts, String fact) {
    if (Facts.text.contains(fact)) return facts.one(fact);
    final codes = Facts.multi.contains(fact) ? facts.many(fact) : [if (facts.one(fact) case final v?) v];
    if (codes.isEmpty) return null;
    return codes.map((c) => label(fact, c)).join(', ');
  }

  factory ProfileOptions.fromJson(Object? json) => ProfileOptions({
        for (final e in asJsonMap(json).entries)
          e.key: asList<FactOption>(
            e.value,
            (o) => FactOption(asString(o['code']), asString(o['label'])),
          ),
      });
}

/// One fact filter: answers I accept, and whether to show those who did not say.
class FactFilter {
  const FactFilter({required this.accept, this.unspecified = true});

  final List<String> accept;
  final bool unspecified;

  Map<String, Object> toJson() => {'accept': accept, 'unspecified': unspecified};

  static Map<String, FactFilter> mapFromJson(Object? json) => {
        for (final e in asJsonMap(json).entries)
          e.key: FactFilter(
            accept: asStringList(asJsonMap(e.value)['accept']),
            unspecified: asBool(asJsonMap(e.value)['unspecified'], true),
          ),
      };
}
