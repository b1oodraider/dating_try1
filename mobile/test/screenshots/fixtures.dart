// Fake backend for the screenshot harness: JSON shaped like the models'
// fromJson (lib/core/models) for the endpoints of lib/core/api/api_client.dart.
import 'dart:convert';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:http/http.dart' as http;

const String baseUrl = 'http://api.test/api/v1';

/// Photo paths: `/api/v1/media/photos/<folder>/<n>.png`; the fake HttpClient
/// maps `<folder>/<n>.png` to a file of the photos dir.
Map<String, Object?> _photo(String folder, int n, {int? order}) => {
      'id': '$folder-$n',
      'url': '/api/v1/media/photos/$folder/$n.png',
      'mimeType': 'image/png',
      'sizeBytes': 120000,
      'sortOrder': order ?? n,
      'isPrimary': (order ?? n) == 1,
      'createdAt': '2026-09-01T10:00:00Z',
    };

Map<String, Object?> _tag(String code, String category, String categoryLabel,
        String label, int order) =>
    {
      'code': code,
      'category': category,
      'categoryLabel': categoryLabel,
      'label': label,
      'description': label,
      'sortOrder': order,
    };

final List<Map<String, Object?>> tags = [
  _tag('SLOW_PACE', 'pace', 'Темп', 'Не тороплюсь', 1),
  _tag('QUICK_MEET', 'pace', 'Темп', 'Быстро к встрече', 2),
  _tag('LONG_TEXTS', 'format', 'Формат', 'Длинные сообщения', 3),
  _tag('VOICE', 'format', 'Формат', 'Голосовые', 4),
  _tag('CALLS_OK', 'calls', 'Звонки', 'Люблю созвоны', 5),
  _tag('NO_CALLS', 'calls', 'Звонки', 'Без звонков сначала', 6),
  _tag('CLEAR_BOUNDS', 'boundaries', 'Границы', 'Чёткие границы', 7),
  _tag('INTROVERT', 'social', 'Общение', 'Интроверт', 8),
  _tag('PARTY', 'social', 'Общение', 'Люблю компании', 9),
];

const List<Map<String, Object?>> goals = [
  {
    'code': 'SERIOUS_RELATIONSHIP',
    'label': 'Серьёзные отношения',
    'sortOrder': 1
  },
  {'code': 'FAMILY', 'label': 'Семья', 'sortOrder': 2},
  {'code': 'DATING', 'label': 'Свидания', 'sortOrder': 3},
  {'code': 'FRIENDSHIP', 'label': 'Дружба', 'sortOrder': 4},
  {'code': 'CHAT', 'label': 'Общение', 'sortOrder': 5},
];

Map<String, Object?> _interest(
        String code, String category, String categoryLabel, String label, String emoji) =>
    {
      'code': code,
      'category': category,
      'categoryLabel': categoryLabel,
      'label': label,
      'emoji': emoji,
    };

final List<Map<String, Object?>> interests = [
  _interest('RUNNING', 'sport', 'Спорт', 'Бег', '🏃'),
  _interest('YOGA', 'sport', 'Спорт', 'Йога', '🧘'),
  _interest('MUSEUMS', 'culture', 'Культура', 'Музеи', '🏛️'),
  _interest('BOOKS', 'culture', 'Культура', 'Книги', '📚'),
  _interest('COFFEE', 'food', 'Еда', 'Кофе', '☕'),
  _interest('TRAVEL', 'travel', 'Путешествия', 'Путешествия', '✈️'),
  _interest('PAINTING', 'creative', 'Творчество', 'Рисование', '🎨'),
  _interest('SUP', 'sport', 'Спорт', 'Сапборд', '🛶'),
];

final List<Map<String, Object?>> artists = [
  {'id': 1, 'name': 'Земфира'},
  {'id': 2, 'name': 'Кино'},
  {'id': 3, 'name': 'Radiohead'},
  {'id': 4, 'name': 'Мумий Тролль'},
];

Map<String, Object?> _profile(
  String id,
  String name,
  int age,
  String city, {
  required String folder,
  int photos = 1,
  String? about,
  String? status,
  List<int> tagIdx = const [],
  List<int> goalIdx = const [],
  int? height,
  bool premium = false,
  bool verified = false,
  int? compatibility,
  int? distance,
  List<int> interestIdx = const [],
  Map<String, String> interestComments = const {},
  List<int> artistIdx = const [],
  String? zodiac,
  List<String> common = const [],
  Map<String, Object?> facts = const {},
}) =>
    {
      'id': id,
      'displayName': name,
      'age': age,
      'city': city,
      'aboutText': about,
      'dailyStatusText': status,
      'tags': [for (final i in tagIdx) tags[i]],
      // The viewer shares the first card (highlighted in the full profile).
      'sharedTags': [for (final i in tagIdx.take(1)) tags[i]['code']],
      'photos': [for (var n = 1; n <= photos; n++) _photo(folder, n)],
      'heightCm': height,
      'goals': [for (final i in goalIdx) goals[i]],
      'premium': premium,
      'verified': verified,
      'compatibility': compatibility,
      'distanceKm': distance,
      'interests': [
        for (final i in interestIdx)
          {...interests[i], 'comment': interestComments[interests[i]['code']]},
      ],
      'artists': [for (final i in artistIdx) artists[i]],
      'zodiac': zodiac,
      'common': common,
      'facts': facts,
    };

final List<Map<String, Object?>> feed = [
  _profile('p1', 'Анна', 26, 'Москва',
      folder: '01-female',
      photos: 3,
      facts: {
        'kidsPlans': 'WANT',
        'smoking': 'NO',
        'sport': 'REGULARLY',
        'pets': ['DOG'],
        'profession': 'UX-дизайнер',
        'educationLevel': 'HIGHER',
        'languages': ['RU', 'EN'],
      },
      about: 'Люблю утренние пробежки по набережной, кофе без сахара и '
          'долгие разговоры о книгах. Ищу того, с кем можно молчать.',
      status: 'Сегодня иду на выставку Айвазовского',
      tagIdx: [0, 2, 6, 7],
      goalIdx: [0, 1],
      height: 168,
      premium: true,
      verified: true,
      compatibility: 87,
      distance: 3,
      interestIdx: [0, 1, 2, 3, 4, 5],
      interestComments: {'COFFEE': 'флэт уайт и раф', 'TRAVEL': 'Грузия и Алтай, хочу в Японию'},
      artistIdx: [0, 1, 2],
      zodiac: 'Лев',
      common: ['Бег', 'Земфира']),
  _profile('p2', 'Мария', 24, 'Москва',
      folder: '02-female',
      photos: 3,
      about: 'Дизайнер интерфейсов. Рисую акварелью, катаюсь на сапборде.',
      status: 'Ищу компанию на концерт в субботу',
      tagIdx: [1, 3, 4, 8],
      goalIdx: [2, 4],
      height: 172,
      verified: true,
      compatibility: 64,
      distance: 7,
      interestIdx: [6, 7, 5],
      artistIdx: [3],
      zodiac: 'Рыбы',
      common: ['Путешествия']),
  _profile('p3', 'Екатерина', 29, 'Санкт-Петербург',
      folder: '04-female',
      photos: 2,
      about: 'Архитектор, обожаю белые ночи и прогулки по крышам.',
      tagIdx: [0, 5, 6],
      goalIdx: [0],
      height: 165,
      premium: true,
      compatibility: 72),
  _profile('p4', 'Ольга', 27, 'Москва',
      folder: '05-female',
      about: 'Врач-педиатр. Пеку лучшие сырники в районе.',
      status: 'На дежурстве до вечера',
      tagIdx: [2, 7],
      goalIdx: [1],
      distance: 12),
];

final List<Map<String, Object?>> likers = [
  _profile('l1', 'Дарья', 25, 'Москва',
      folder: '06-female', verified: true, compatibility: 78, distance: 4),
  _profile('l2', 'Алина', 23, 'Москва', folder: '07-female', premium: true),
  _profile('l3', 'София', 28, 'Санкт-Петербург', folder: '08-female'),
  _profile('l4', 'Виктория', 30, 'Москва', folder: '09-female', verified: true),
  _profile('l5', 'Полина', 26, 'Москва', folder: '10-female'),
];

Map<String, Object?> _message(
        String id, String matchId, bool mine, String text, String time) =>
    {
      'id': id,
      'matchId': matchId,
      'mine': mine,
      'type': 'TEXT',
      'text': text,
      'createdAt': '2026-10-20T$time:00Z',
    };

/// Newest first, as `GET /matches/{id}/messages` pages them.
final List<Map<String, Object?>> conversation = [
  for (final (i, (mine, text, time)) in const [
    (false, 'Привет! Увидела, что ты тоже любишь Айвазовского)', '18:02'),
    (
      true,
      'Привет, Анна! Да, был на выставке в Третьяковке в прошлом году',
      '18:05'
    ),
    (false, 'Завидую! Я как раз собираюсь в эту субботу', '18:06'),
    (true, 'Можно составить компанию? Потом можно выпить кофе рядом', '18:10'),
    (false, 'Звучит отлично. Только чур кофе без сахара!', '18:12'),
    (true, 'Договорились. Во сколько тебе удобно?', '18:13'),
    (false, 'Давай в 12 у главного входа?', '18:20'),
    (true, 'Отлично, до субботы!', '18:21'),
  ].indexed)
    _message('m${i + 1}', 'match1', mine, text, time),
  {
    'id': 'm-plan',
    'matchId': 'match1',
    'mine': false,
    'type': 'DATE_PLAN',
    'text': 'Предлагаю встречу: Третьяковская галерея, 25.10 в 12:00 и ещё 1',
    'datePlanId': 'plan1',
    'createdAt': '2026-10-21T18:25:00Z',
  },
].reversed.toList();

final Map<String, Object?> sponsoredVenue = {
  'id': 'v1',
  'name': 'Кофейня «Зёрна»',
  'address': 'Лаврушинский пер., 3',
  'latitude': 55.7415,
  'longitude': 37.6208,
  'category': 'COFFEE',
  'description': 'Тихий зал на втором этаже',
  'ad': {'label': 'Реклама', 'advertiser': 'ООО «Зёрна»', 'erid': '2VtzqX5'},
};

final Map<String, Object?> datePlan = {
  'id': 'plan1',
  'matchId': 'match1',
  'status': 'PROPOSED',
  'version': 1,
  'mine': false,
  'lastEditMine': false,
  'canRespond': true,
  'canEdit': true,
  'items': [
    {
      'placeName': 'Третьяковская галерея',
      'address': 'Лаврушинский пер., 10',
      'latitude': 55.7414,
      'longitude': 37.6208,
      'startsAt': '2026-10-25T09:00:00Z',
      'activity': 'CULTURE',
      'comment': 'Встречаемся у главного входа',
    },
    {
      'placeName': 'Кофейня «Зёрна»',
      'address': 'Лаврушинский пер., 3',
      'latitude': 55.7415,
      'longitude': 37.6230,
      'startsAt': '2026-10-25T11:30:00Z',
      'activity': 'COFFEE',
      'venue': sponsoredVenue,
    },
  ],
};

final List<Map<String, Object?>> matches = [
  {
    'id': 'match1',
    'profile': feed[0],
    'lastMessage': conversation.first,
    'unreadCount': 0,
    'otherLastReadMessageId': 'm7',
    'createdAt': '2026-10-19T12:00:00Z',
  },
  {
    'id': 'match2',
    'profile': likers[0],
    'lastMessage': _message('x1', 'match2', false,
        'А ты какие фильмы любишь? Я тут пересматриваю Тарковского', '17:40'),
    'unreadCount': 2,
    'createdAt': '2026-10-18T12:00:00Z',
  },
  {
    'id': 'match3',
    'profile': _profile('p9', 'Дмитрий', 31, 'Москва',
        folder: '21-male', verified: true),
    'lastMessage':
        _message('x2', 'match3', true, 'Хорошо, спишемся завтра', '12:15'),
    'unreadCount': 0,
    'createdAt': '2026-10-17T12:00:00Z',
  },
  {
    'id': 'match4',
    'profile': likers[2],
    'lastMessage': null,
    'unreadCount': 1,
    'createdAt': '2026-10-20T09:00:00Z',
  },
];

Map<String, Object?> _item(
        String code, String kind, String category, String title, int qty,
        {String? rarity}) =>
    {
      'code': code,
      'kind': kind,
      'category': category,
      'title': title,
      'rarity': rarity,
      'equipSlot': null,
      'attributes': <String, Object?>{},
      'quantity': qty,
      'periodicQuantity': 0,
    };

final Map<String, Object?> wallet = {
  'premium': {'active': true, 'until': '2026-11-20T00:00:00Z'},
  'premiumFeatures': ['ADVANCED_FILTERS', 'EXCLUSION_WORDS'],
  'items': [
    _item('SUPERLIKE', 'CONSUMABLE', 'SUPERLIKE', 'Суперлайк', 3),
    _item('GACHA_TICKET', 'CONSUMABLE', 'GACHA_TICKET', 'Билет удачи', 7),
    _item('BOOST', 'CONSUMABLE', 'BOOST', 'Буст', 1),
  ],
  'equipped': <String, String>{},
};

final List<Map<String, Object?>> banners = [
  {
    'code': 'AUTUMN',
    'title': 'Осенний листопад',
    'description': 'Редкие рамки для фото и стикеры с осенним настроением. '
        'Шанс на легендарную рамку повышен!',
    'type': 'LIMITED',
    'endsAt': '2026-11-05T00:00:00Z',
    'open': true,
    'cost': {
      'itemCode': 'GACHA_TICKET',
      'perPull': 1,
      'multiPullSize': 10,
      'multiPullCost': 9
    },
    'myPity': {
      'epicGuaranteedIn': 4,
      'legendaryGuaranteedIn': 52,
      'totalPulls': 28
    },
    'rates': {
      'rarities': [
        {'rarity': 'COMMON', 'percent': 70.0},
        {'rarity': 'RARE', 'percent': 22.0},
        {'rarity': 'EPIC', 'percent': 7.0},
        {'rarity': 'LEGENDARY', 'percent': 1.0},
      ],
    },
    'featured': [
      {
        'entryId': 'frame',
        'rarity': 'LEGENDARY',
        'reward': {
          'type': 'ITEM',
          'item': _item(
              'FRAME_MAPLE', 'COSMETIC', 'PHOTO_FRAME', 'Кленовая рамка', 1,
              rarity: 'LEGENDARY'),
          'quantity': 1,
        },
        'percent': 0.5,
        'featured': true,
      },
      {
        'entryId': 'premium3',
        'rarity': 'EPIC',
        'reward': {'type': 'PREMIUM_DAYS', 'days': 3},
        'percent': 1.0,
        'featured': true,
      },
    ],
  },
  {
    'code': 'STANDARD',
    'title': 'Стандартный',
    'description': 'Суперлайки, бусты и стикеры',
    'type': 'PERMANENT',
    'open': true,
    'cost': {
      'itemCode': 'GACHA_TICKET',
      'perPull': 1,
      'multiPullSize': 10,
      'multiPullCost': 10
    },
    'myPity': {
      'epicGuaranteedIn': 9,
      'legendaryGuaranteedIn': 80,
      'totalPulls': 10
    },
    'rates': {
      'rarities': [
        {'rarity': 'COMMON', 'percent': 75.0},
        {'rarity': 'RARE', 'percent': 20.0},
        {'rarity': 'EPIC', 'percent': 5.0},
      ],
    },
    'featured': [
      {
        'entryId': 'sl3',
        'rarity': 'RARE',
        'reward': {
          'type': 'ITEM',
          'item': _item('SUPERLIKE', 'CONSUMABLE', 'SUPERLIKE', 'Суперлайк', 3),
          'quantity': 3,
        },
        'percent': 5.0,
        'featured': true,
      },
    ],
  },
];

/// Off for the shot of the «Подтвердите анкету» banner.
bool myProfileVerified = true;

final Map<String, Object?> myProfile = {
  'id': 'me',
  'displayName': 'Иван',
  'age': 29,
  'gender': 'MALE',
  'seeking': 'FEMALE',
  'birthDate': '1997-05-14',
  'ageFrom': 23,
  'ageTo': 32,
  'city': 'Москва',
  'cityId': 1,
  'searchRadiusKm': 25,
  'locationShared': true,
  'invisible': false,
  'showPremiumBadge': true,
  'aboutText': 'Инженер-программист. По выходным — велосипед и походы, '
      'по вечерам — настолки с друзьями. Готовлю отличный плов.',
  'dailyStatusText': 'Ищу, с кем сходить в кино на выходных',
  'publicationStatus': 'PUBLISHED',
  'tags': [tags[0], tags[2], tags[4], tags[6]],
  'photos': [
    _photo('19-male', 1),
    _photo('19-male', 2),
    _photo('20-male', 1, order: 3)
  ],
  'tips': [
    {
      'code': 'HEIGHT',
      'text': 'Укажите рост',
      'weight': 5,
      'done': false,
      'requiredToPublish': false
    },
    {
      'code': 'MORE_PHOTOS',
      'text': 'Добавьте ещё фото',
      'weight': 10,
      'done': false,
      'requiredToPublish': false
    },
    {
      'code': 'ABOUT',
      'text': 'Расскажите о себе',
      'weight': 15,
      'done': true,
      'requiredToPublish': false
    },
  ],
  'completenessPercent': 85,
  'missingForPublish': <String>[],
  'verified': true,
  'heightCm': 182,
  'goals': [goals[0], goals[2]],
  'premium': true,
  'interests': [
    {...interests[0], 'comment': 'по утрам, 5 км'},
    interests[5],
  ],
  'artists': [artists[1]],
  'zodiac': 'Телец',
  'showZodiac': true,
  'facts': {'smoking': 'NO', 'alcohol': 'RARELY', 'pets': ['CAT']},
};

List<Map<String, String>> _opts(List<String> pairs) => [
      for (var i = 0; i < pairs.length; i += 2) {'code': pairs[i], 'label': pairs[i + 1]},
    ];

final Map<String, Object?> profileOptions = {
  'kidsHas': _opts(['YES', 'Есть дети', 'NO', 'Детей нет']),
  'kidsPlans': _opts(['WANT', 'Хочу детей', 'DONT_WANT', 'Не хочу детей', 'UNDECIDED', 'Пока не решил(а)']),
  'smoking': _opts(['NO', 'Не курю', 'SOMETIMES', 'Иногда', 'CIGARETTES', 'Курю сигареты', 'VAPE', 'Вейп, кальян']),
  'alcohol': _opts(['NO', 'Не пью', 'RARELY', 'Редко', 'MODERATELY', 'Умеренно', 'OFTEN', 'Часто']),
  'sport': _opts(['REGULARLY', 'Регулярно', 'SOMETIMES', 'Иногда', 'NO', 'Не занимаюсь']),
  'pets': _opts(['CAT', 'Кошка', 'DOG', 'Собака', 'OTHER', 'Другие животные', 'NONE', 'Нет животных']),
  'educationLevel': _opts(['SCHOOL', 'Среднее', 'COLLEGE', 'Среднее специальное', 'HIGHER', 'Высшее']),
  'languages': _opts(['RU', 'Русский', 'EN', 'Английский', 'DE', 'Немецкий']),
};

http.Response _json(Object? body, [int status = 200]) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// Answers every request of the signed-in app; unknown paths get a 404 and a
/// log line so a missing fixture is visible in the test output.
http.Response respond(http.Request request) {
  final path = request.url.path.replaceFirst('/api/v1', '');
  final get = request.method == 'GET';
  if (path == '/auth/otp/request') {
    return _json({
      'challengeId': 'ch1',
      'expiresAt': '2026-10-21T12:05:00Z',
      'maskedPhone': '+7 *** ***-45-67',
      'devMockCode': null,
    });
  }
  if (path == '/me' && get) {
    return _json({
      'id': 'me',
      'phoneMasked': '+7 *** ***-45-67',
      'status': 'ACTIVE',
      'createdAt': '2026-01-10T10:00:00Z',
      'testAccount': false,
    });
  }
  if (path == '/me/profile' && get) return _json({...myProfile, 'verified': myProfileVerified});
  if (path == '/me/wallet') return _json(wallet);
  if (path == '/me/restrictions') return _json(<Object>[]);
  if (path == '/venues') return _json(<Object>[]);
  if (path == '/places/search') {
    return _json([
      {'name': 'Кофемания', 'address': 'Никольская улица 10, Москва', 'latitude': 55.757, 'longitude': 37.622},
      {'name': 'Кофемания', 'address': 'Неглинная улица, Москва', 'latitude': 55.763, 'longitude': 37.620},
    ]);
  }
  if (path == '/profile-options') return _json(profileOptions);
  if (path == '/me/fact-filters') {
    return _json({'smoking': {'accept': ['NO', 'SOMETIMES'], 'unspecified': true}});
  }
  if (path == '/me/boost') {
    return _json({'active': false, 'impressions': 0, 'available': 2, 'durationMinutes': 30,
        'now': '2026-10-21T12:00:00Z', 'history': <Object>[]});
  }
  if (path == '/me/verification') return _json({'verified': true});
  if (path == '/me/excluded-goals') {
    return _json({'goalCodes': <String>[], 'active': true});
  }
  if (path == '/me/exclusion-words') {
    return _json({'words': <String>[], 'active': true});
  }
  if (path == '/me/achievements') return _json(<Object>[]);
  if (path == '/tags') return _json(tags);
  if (path == '/interests') {
    final groups = <String, List<Map<String, Object?>>>{};
    for (final i in interests) {
      groups.putIfAbsent(i['categoryLabel']! as String, () => []).add(i);
    }
    return _json([
      for (final e in groups.entries)
        {'code': e.value.first['category'], 'label': e.key, 'items': e.value},
    ]);
  }
  if (path == '/artists') return _json(artists);
  if (path == '/support/tickets' && get) return _json(supportTickets);
  if (path.startsWith('/support/tickets/')) return _json(supportTicket);
  if (path == '/goals') return _json(goals);
  if (path == '/discovery/profiles') {
    return _json({
      'items': feed,
      'page': 0,
      'limit': 20,
      'totalElements': feed.length,
      'totalPages': 1,
    });
  }
  if (path == '/discovery/views') return _json(<String, Object?>{});
  if (path == '/me/passes/refresh') return _json({'restored': 0});
  if (path == '/me/likes/received') {
    return _json({
      'items': [
        for (final (i, p) in likers.indexed)
          {'profile': p, 'superlike': i == 1},
      ],
      'totalElements': likers.length,
    });
  }
  if (path == '/me/likes/received/count') {
    return _json({'count': likers.length});
  }
  if (path == '/matches') return _json(matches);
  if (path.endsWith('/date-plan') && get) return _json({'plan': datePlan});
  if (path == '/date-plans/plan1') return _json(datePlan);
  if (path == '/venues') return _json([sponsoredVenue]);
  if (path.startsWith('/matches/') && path.endsWith('/messages') && get) {
    return _json({'items': conversation, 'nextBefore': null});
  }
  if (path.startsWith('/matches/') && path.endsWith('/read')) return _json({});
  if (path == '/gacha/banners') return _json(banners);
  if (path == '/gacha/shards') {
    return _json({
      'balance': {'quantity': 45},
      'shardsPerTicket': 30,
      'maxTicketsPerExchange': 100,
    });
  }
  if (path == '/testing/state') {
    return _json({
      'accountId': 'me',
      'clockOffsetDays': 0,
      'economyNow': '2026-10-21T12:00:00Z',
      'wallet': wallet,
      'achievements': <Object>[],
    });
  }
  if (path == '/testing/profiles') return _json(<Object>[]);
  debugPrint(
      'screenshots fake backend: no fixture for ${request.method} $path');
  return _json({'code': 'NOT_FOUND', 'message': 'Не найдено'}, 404);
}

final List<Map<String, Object?>> supportTickets = [
  {
    'id': 't1',
    'category': 'LIKES_MATCHES',
    'status': 'ANSWERED',
    'createdAt': '2026-10-20T10:00:00Z',
    'updatedAt': '2026-10-20T12:00:00Z',
    'lastMessageAt': '2026-10-20T12:00:00Z',
  },
];

final Map<String, Object?> supportTicket = {
  ...supportTickets.first,
  'messages': [
    {
      'id': 'm1',
      'fromStaff': false,
      'text': 'Вчера были два лайка, сегодня их нет. Куда они делись?',
      'createdAt': '2026-10-20T10:00:00Z',
    },
    {
      'id': 'm2',
      'fromStaff': true,
      'text': 'Здравствуйте! Один человек скрыл анкету, второму вы уже ответили — он теперь в «Чатах».',
      'createdAt': '2026-10-20T12:00:00Z',
    },
  ],
};
