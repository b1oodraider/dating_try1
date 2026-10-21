import 'json_utils.dart';

/// Ticket topics (docs/support.md).
const Map<String, String> supportCategories = {
  'ACCOUNT': 'Аккаунт и вход',
  'PROFILE': 'Анкета и фото',
  'LIKES_MATCHES': 'Лайки и пары',
  'PAYMENTS': 'Покупки и премиум',
  'SAFETY': 'Безопасность и жалобы',
  'BUG': 'Ошибка в приложении',
  'OTHER': 'Другое',
};

const Map<String, String> supportStatuses = {
  'OPEN': 'Ждёт ответа',
  'ANSWERED': 'Есть ответ',
  'CLOSED': 'Закрыто',
};

class SupportMessage {
  const SupportMessage({required this.id, required this.fromStaff, required this.text, this.createdAt});

  final String id;
  final bool fromStaff;
  final String text;
  final DateTime? createdAt;

  factory SupportMessage.fromJson(Map<String, dynamic> json) => SupportMessage(
        id: asString(json['id']),
        fromStaff: asBool(json['fromStaff']),
        text: asString(json['text']),
        createdAt: asDateTimeOrNull(json['createdAt']),
      );
}

/// A ticket; [messages] are empty in the list (`TicketSummary`).
class SupportTicket {
  const SupportTicket({
    required this.id,
    required this.category,
    required this.status,
    this.lastMessageAt,
    this.messages = const <SupportMessage>[],
  });

  final String id;
  final String category;
  final String status;
  final DateTime? lastMessageAt;
  final List<SupportMessage> messages;

  String get categoryLabel => supportCategories[category] ?? category;
  String get statusLabel => supportStatuses[status] ?? status;

  factory SupportTicket.fromJson(Map<String, dynamic> json) => SupportTicket(
        id: asString(json['id']),
        category: asString(json['category']),
        status: asString(json['status']),
        lastMessageAt: asDateTimeOrNull(json['lastMessageAt']),
        messages: asList<SupportMessage>(json['messages'], SupportMessage.fromJson),
      );
}
