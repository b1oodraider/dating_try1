import 'json_utils.dart';
import 'public_profile.dart';

/// Answer of like / super like / pass (contract `ReactionResult`).
class ReactionResult {
  const ReactionResult({required this.matched, this.matchId, this.queued = false});

  final bool matched;

  /// Only when [matched].
  final String? matchId;

  /// The like waits until my selfie is verified.
  final bool queued;

  factory ReactionResult.fromJson(Map<String, dynamic> json) => ReactionResult(
        matched: asBool(json['matched']),
        matchId: asStringOrNull(json['matchId']),
        queued: asBool(json['queued']),
      );
}

/// Chat message (contract `Message`).
class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.matchId,
    required this.mine,
    required this.type,
    this.text,
    this.stickerPackCode,
    this.sticker,
    this.clientMessageId,
    this.createdAt,
    this.datePlanId,
  });

  final String id;
  final String matchId;
  final bool mine;

  /// TEXT, STICKER or DATE_PLAN (posted by the server for a plan change; [text] is its summary).
  final String type;

  /// DATE_PLAN messages only.
  final String? datePlanId;
  final String? text;
  final String? stickerPackCode;
  final String? sticker;
  final String? clientMessageId;
  final DateTime? createdAt;

  bool get isSticker => type == 'STICKER';

  bool get isDatePlan => type == 'DATE_PLAN';

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
        id: asString(json['id']),
        matchId: asString(json['matchId']),
        mine: asBool(json['mine']),
        type: asString(json['type'], 'TEXT'),
        text: asStringOrNull(json['text']),
        stickerPackCode: asStringOrNull(json['stickerPackCode']),
        sticker: asStringOrNull(json['sticker']),
        clientMessageId: asStringOrNull(json['clientMessageId']),
        createdAt: asDateTimeOrNull(json['createdAt']),
        datePlanId: asStringOrNull(json['datePlanId']),
      );
}

/// A mutual like with its chat state (contract `Match`).
class Match {
  const Match({
    required this.id,
    required this.profile,
    this.lastMessage,
    this.unreadCount = 0,
    this.myLastReadMessageId,
    this.otherLastReadMessageId,
    this.createdAt,
    this.otherLastSeenAt,
    this.otherOnline = false,
  });

  final String id;

  /// The other person.
  final PublicProfile profile;
  final ChatMessage? lastMessage;
  final int unreadCount;
  final String? myLastReadMessageId;

  /// Read receipt: the other person read the chat up to this message.
  final String? otherLastReadMessageId;
  final DateTime? createdAt;

  /// Presence of the other person when the chat list was loaded.
  final DateTime? otherLastSeenAt;
  final bool otherOnline;

  /// «в сети», «был(а) 5 мин назад», «был(а) сегодня в 14:05», «был(а) 12.10»; null when not known.
  String? presenceText([DateTime? now]) {
    if (otherOnline) return 'в сети';
    final seen = otherLastSeenAt?.toLocal();
    if (seen == null) return null;
    final at = (now ?? DateTime.now()).toLocal();
    final minutes = at.difference(seen).inMinutes;
    String two(int v) => v.toString().padLeft(2, '0');
    if (minutes < 60) return 'был(а) ${minutes < 1 ? 1 : minutes} мин назад';
    final sameDay = seen.year == at.year && seen.month == at.month && seen.day == at.day;
    if (sameDay) return 'был(а) сегодня в ${two(seen.hour)}:${two(seen.minute)}';
    final yesterday = at.subtract(const Duration(days: 1));
    if (seen.year == yesterday.year && seen.month == yesterday.month && seen.day == yesterday.day) {
      return 'был(а) вчера в ${two(seen.hour)}:${two(seen.minute)}';
    }
    return 'был(а) ${two(seen.day)}.${two(seen.month)}';
  }

  Match copyWith({
    ChatMessage? lastMessage,
    int? unreadCount,
    String? myLastReadMessageId,
    String? otherLastReadMessageId,
  }) =>
      Match(
        id: id,
        profile: profile,
        lastMessage: lastMessage ?? this.lastMessage,
        unreadCount: unreadCount ?? this.unreadCount,
        myLastReadMessageId: myLastReadMessageId ?? this.myLastReadMessageId,
        otherLastReadMessageId:
            otherLastReadMessageId ?? this.otherLastReadMessageId,
        createdAt: createdAt,
        otherLastSeenAt: otherLastSeenAt,
        otherOnline: otherOnline,
      );

  factory Match.fromJson(Map<String, dynamic> json) {
    final last = json['lastMessage'];
    return Match(
      id: asString(json['id']),
      profile: PublicProfile.fromJson(asJsonMap(json['profile'])),
      lastMessage:
          last is Map<String, dynamic> ? ChatMessage.fromJson(last) : null,
      unreadCount: asInt(json['unreadCount']),
      myLastReadMessageId: asStringOrNull(json['myLastReadMessageId']),
      otherLastReadMessageId: asStringOrNull(json['otherLastReadMessageId']),
      createdAt: asDateTimeOrNull(json['createdAt']),
      otherLastSeenAt: asDateTimeOrNull(json['otherLastSeenAt']),
      otherOnline: asBool(json['otherOnline']),
    );
  }
}

/// One page of `GET /matches/{id}/messages`: newest first.
class MessagePage {
  const MessagePage({required this.items, this.nextBefore});

  final List<ChatMessage> items;

  /// Pass as `before` for older messages; null = nothing older.
  final String? nextBefore;

  factory MessagePage.fromJson(Map<String, dynamic> json) => MessagePage(
        items: asList<ChatMessage>(json['items'], ChatMessage.fromJson),
        nextBefore: asStringOrNull(json['nextBefore']),
      );
}

/// Someone who liked me (contract `ReceivedLike`).
class ReceivedLike {
  const ReceivedLike({required this.profile, required this.superlike});

  final PublicProfile profile;
  final bool superlike;

  factory ReceivedLike.fromJson(Map<String, dynamic> json) => ReceivedLike(
        profile: PublicProfile.fromJson(asJsonMap(json['profile'])),
        superlike: asBool(json['superlike']),
      );
}

class ReceivedLikesPage {
  const ReceivedLikesPage({required this.items, required this.totalElements});

  final List<ReceivedLike> items;
  final int totalElements;

  factory ReceivedLikesPage.fromJson(Map<String, dynamic> json) =>
      ReceivedLikesPage(
        items: asList<ReceivedLike>(json['items'], ReceivedLike.fromJson),
        totalElements: asInt(json['totalElements']),
      );
}

/// One of my active restrictions (`GET /me/restrictions`).
class Restriction {
  const Restriction({required this.kind, required this.reason, this.endsAt});

  /// LIKES, MESSAGES, HIDDEN, SUSPENDED, VERIFICATION_REQUIRED.
  final String kind;
  final String reason;

  /// null = until a moderator lifts it.
  final DateTime? endsAt;

  factory Restriction.fromJson(Map<String, dynamic> json) => Restriction(
        kind: asString(json['kind']),
        reason: asString(json['reason']),
        endsAt: asDateTimeOrNull(json['endsAt']),
      );
}

/// Selfie verification request (contract `VerificationRequest`).
class VerificationRequest {
  const VerificationRequest({
    required this.id,
    required this.status,
    required this.instruction,
    this.rejectReason,
  });

  final String id;

  /// AWAITING_SELFIE, PENDING, APPROVED, REJECTED, EXPIRED.
  final String status;

  /// The gesture to show, as text.
  final String instruction;
  final String? rejectReason;

  factory VerificationRequest.fromJson(Map<String, dynamic> json) =>
      VerificationRequest(
        id: asString(json['id']),
        status: asString(json['status']),
        instruction: asString(json['instruction']),
        rejectReason: asStringOrNull(json['rejectReason']),
      );
}

/// `GET /me/verification`.
class VerificationState {
  const VerificationState({required this.verified, this.current});

  final bool verified;
  final VerificationRequest? current;

  factory VerificationState.fromJson(Map<String, dynamic> json) {
    final current = json['current'];
    return VerificationState(
      verified: asBool(json['verified']),
      current: current is Map<String, dynamic>
          ? VerificationRequest.fromJson(current)
          : null,
    );
  }
}

/// A QA seed profile (`GET /testing/profiles`): who can like or write to me.
class SeedProfileRef {
  const SeedProfileRef({
    required this.accountId,
    required this.name,
    required this.published,
  });

  final String accountId;
  final String name;
  final bool published;

  factory SeedProfileRef.fromJson(Map<String, dynamic> json) {
    final profile = asJsonMap(json['profile']);
    return SeedProfileRef(
      accountId: asString(json['accountId']),
      name: asString(profile['displayName'], 'Без имени'),
      published: profile['publicationStatus'] == 'PUBLISHED',
    );
  }
}
