import 'json_utils.dart';

/// The caller's account (`GET /me`, contract type `Account`).
class Account {
  const Account({
    required this.id,
    required this.phoneMasked,
    required this.status,
    required this.createdAt,
    required this.testAccount,
  });

  final String id;
  final String? phoneMasked;

  /// ACTIVE | SUSPENDED | DELETED.
  final String status;
  final DateTime? createdAt;

  /// QA account: may use the testing API, so the app shows the test panel.
  final bool testAccount;

  factory Account.fromJson(Map<String, dynamic> json) {
    return Account(
      id: asString(json['id']),
      phoneMasked: asStringOrNull(json['phoneMasked']),
      status: asString(json['status']),
      createdAt: asDateTimeOrNull(json['createdAt']),
      testAccount: asBool(json['testAccount']),
    );
  }
}
