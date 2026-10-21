import 'package:dating_mobile/core/models/public_profile.dart';
import 'package:dating_mobile/core/models/social.dart';
import 'package:flutter_test/flutter_test.dart';

Match _match({bool online = false, DateTime? seen}) => Match(
      id: 'm',
      profile: PublicProfile.fromJson(const {'id': 'p', 'displayName': 'Аня'}),
      otherOnline: online,
      otherLastSeenAt: seen,
    );

void main() {
  final now = DateTime(2026, 10, 21, 15, 0);

  test('presence text', () {
    expect(_match(online: true).presenceText(now), 'в сети');
    expect(_match().presenceText(now), isNull);
    expect(_match(seen: DateTime(2026, 10, 21, 14, 55)).presenceText(now), 'был(а) 5 мин назад');
    expect(_match(seen: DateTime(2026, 10, 21, 9, 7)).presenceText(now), 'был(а) сегодня в 09:07');
    expect(_match(seen: DateTime(2026, 10, 20, 23, 30)).presenceText(now), 'был(а) вчера в 23:30');
    expect(_match(seen: DateTime(2026, 10, 12, 10, 0)).presenceText(now), 'был(а) 12.10');
  });
}
