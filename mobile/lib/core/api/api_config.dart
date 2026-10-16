import 'dart:io' show Platform;

import 'package:flutter/foundation.dart' show kIsWeb;

/// Where the API lives.
///
/// Override at build time:
///   flutter run --dart-define=API_BASE_URL=http://192.168.1.10:8080/api/v1
///
/// Defaults: the Android emulator reaches the host machine via 10.0.2.2,
/// the iOS simulator via localhost.
class ApiConfig {
  ApiConfig._();

  static const String _fromEnvironment = String.fromEnvironment('API_BASE_URL');

  static String get defaultBaseUrl {
    final override = _fromEnvironment.trim();
    if (override.isNotEmpty) return normalizeBaseUrl(override);
    if (!kIsWeb && Platform.isAndroid) return 'http://10.0.2.2:8080/api/v1';
    return 'http://localhost:8080/api/v1';
  }

  /// Strips trailing slashes so that `'$base/path'` never yields `//path`.
  static String normalizeBaseUrl(String url) {
    var result = url.trim();
    while (result.endsWith('/')) {
      result = result.substring(0, result.length - 1);
    }
    return result;
  }
}
