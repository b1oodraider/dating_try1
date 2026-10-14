import 'package:flutter/foundation.dart' show debugPrint;

import '../models/json_utils.dart';

/// Error returned by the API (body `{code, message, traceId, fieldErrors?}`)
/// or produced locally for network failures / malformed responses.
class ApiException implements Exception {
  const ApiException({
    required this.statusCode,
    required this.code,
    required this.message,
    this.fieldErrors = const <String, String>{},
    this.traceId,
  });

  /// HTTP status; 0 when no HTTP response was received (network, timeout).
  final int statusCode;

  /// Machine-readable code, e.g. INVALID_CODE, RATE_LIMITED, NETWORK_ERROR.
  final String code;

  /// User-facing message (Russian), safe to show in the UI.
  final String message;

  /// Per-field validation messages, keyed by request field name.
  final Map<String, String> fieldErrors;

  final String? traceId;

  bool get isUnauthorized => statusCode == 401;
  bool get isNetworkError => statusCode == 0;

  factory ApiException.fromResponse(
    int statusCode,
    Object? body, {
    String? traceIdHeader,
  }) {
    final json = asJsonMap(body);

    final fieldErrors = <String, String>{};
    final rawFieldErrors = json['fieldErrors'];
    if (rawFieldErrors is Map) {
      rawFieldErrors.forEach((key, value) {
        if (value != null) fieldErrors['$key'] = '$value';
      });
    }

    final serverMessage = asStringOrNull(json['message']);
    return ApiException(
      statusCode: statusCode,
      code: asStringOrNull(json['code']) ?? 'HTTP_$statusCode',
      message: (serverMessage == null || serverMessage.trim().isEmpty)
          ? _fallbackMessage(statusCode)
          : serverMessage,
      fieldErrors: fieldErrors,
      traceId: asStringOrNull(json['traceId']) ?? traceIdHeader,
    );
  }

  static String _fallbackMessage(int statusCode) {
    if (statusCode == 401) return 'Требуется вход в аккаунт';
    if (statusCode == 403) return 'Недостаточно прав';
    if (statusCode == 404) return 'Не найдено';
    if (statusCode == 429) return 'Слишком много запросов. Попробуйте позже';
    if (statusCode >= 500) return 'Сервер временно недоступен';
    return 'Ошибка запроса ($statusCode)';
  }

  @override
  String toString() {
    final trace = traceId == null ? '' : ', traceId=$traceId';
    return 'ApiException($statusCode $code: $message$trace)';
  }
}

/// Turns any error into a user-facing message. For validation errors the
/// message of [field] (if the server reported one) wins over the generic one.
String describeError(Object error, {String? field}) {
  if (error is ApiException) {
    final fieldMessage = field == null ? null : error.fieldErrors[field];
    return fieldMessage ?? error.message;
  }
  debugPrint('Unexpected error: $error');
  return 'Что-то пошло не так. Попробуйте ещё раз';
}
