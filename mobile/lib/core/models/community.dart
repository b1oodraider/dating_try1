import 'json_utils.dart';

/// Group kinds in display order (docs/communities.md).
const Map<String, String> communityKinds = {
  'FRANCHISE': 'Вселенные',
  'FILM': 'Фильмы',
  'SERIES': 'Сериалы',
  'BOOK': 'Книги',
  'ANIME': 'Аниме',
  'GAME': 'Игры',
  'MUSIC': 'Музыка',
  'OTHER': 'Другое',
};

/// «1 участник», «3 участника», «12 участников».
String membersText(int n) {
  final last = n % 10;
  final lastTwo = n % 100;
  final word = last == 1 && lastTwo != 11
      ? 'участник'
      : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)
          ? 'участника'
          : 'участников';
  return '$n $word';
}

/// An interest group: a community around a film, book, series, game or franchise.
class Community {
  const Community({
    required this.id,
    required this.title,
    required this.kind,
    required this.kindLabel,
    this.emoji,
    this.description,
    this.members = 0,
    this.subscribed = false,
  });

  final int id;
  final String title;
  final String kind;
  final String kindLabel;
  final String? emoji;
  final String? description;
  final int members;
  final bool subscribed;

  /// «⚡ Гарри Поттер».
  String get display => emoji == null ? title : '$emoji $title';

  factory Community.fromJson(Map<String, dynamic> json) => Community(
        id: asInt(json['id']),
        title: asString(json['title']),
        kind: asString(json['kind']),
        kindLabel: asString(json['kindLabel']),
        emoji: asStringOrNull(json['emoji']),
        description: asStringOrNull(json['description']),
        members: asInt(json['members']),
        subscribed: asBool(json['subscribed']),
      );
}

/// A group on someone's profile; [shared] = I am in it too.
class CommunityRef {
  const CommunityRef({required this.id, required this.title, this.emoji, this.shared = false});

  final int id;
  final String title;
  final String? emoji;
  final bool shared;

  String get display => emoji == null ? title : '$emoji $title';

  factory CommunityRef.fromJson(Map<String, dynamic> json) => CommunityRef(
        id: asInt(json['id']),
        title: asString(json['title']),
        emoji: asStringOrNull(json['emoji']),
        shared: asBool(json['shared']),
      );
}
