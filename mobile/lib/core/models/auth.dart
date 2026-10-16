import 'json_utils.dart';

/// Response of `POST /auth/otp/request`.
class OtpChallenge {
  const OtpChallenge({
    required this.challengeId,
    required this.expiresAt,
    required this.maskedPhone,
    required this.devMockCode,
  });

  final String challengeId;
  final DateTime? expiresAt;
  final String? maskedPhone;

  /// Present ONLY when the server runs in OTP mock mode (local development).
  final String? devMockCode;

  factory OtpChallenge.fromJson(Map<String, dynamic> json) {
    return OtpChallenge(
      challengeId: asString(json['challengeId']),
      expiresAt: asDateTimeOrNull(json['expiresAt']),
      maskedPhone: asStringOrNull(json['maskedPhone']),
      devMockCode: asStringOrNull(json['devMockCode']),
    );
  }
}

/// Response of `POST /auth/otp/verify`.
class AuthToken {
  const AuthToken({
    required this.token,
    required this.expiresAt,
    required this.accountId,
  });

  final String token;
  final DateTime? expiresAt;
  final String accountId;

  factory AuthToken.fromJson(Map<String, dynamic> json) {
    return AuthToken(
      token: asString(json['token']),
      expiresAt: asDateTimeOrNull(json['expiresAt']),
      accountId: asString(json['accountId']),
    );
  }
}
