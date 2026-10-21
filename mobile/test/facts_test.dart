import 'package:dating_mobile/core/models/facts.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('facts keep only what was said and are described with labels', () {
    final facts = Facts.fromJson({
      'smoking': 'VAPE',
      'alcohol': null,
      'pets': ['CAT', 'DOG'],
      'languages': <String>[],
      'profession': 'Дизайнер',
      'university': '',
    });
    expect(facts.values.keys, unorderedEquals(['smoking', 'pets', 'profession']));

    final options = ProfileOptions.fromJson({
      'smoking': [
        {'code': 'VAPE', 'label': 'Вейп, кальян'},
      ],
      'pets': [
        {'code': 'CAT', 'label': 'Кошка'},
        {'code': 'DOG', 'label': 'Собака'},
      ],
    });
    expect(options.describe(facts, 'smoking'), 'Вейп, кальян');
    expect(options.describe(facts, 'pets'), 'Кошка, Собака');
    expect(options.describe(facts, 'profession'), 'Дизайнер');
    expect(options.describe(facts, 'alcohol'), isNull);
  });

  test('fact filters round-trip', () {
    final filters = FactFilter.mapFromJson({
      'smoking': {'accept': ['NO'], 'unspecified': false},
    });
    expect(filters['smoking']!.toJson(), {'accept': ['NO'], 'unspecified': false});
  });
}
