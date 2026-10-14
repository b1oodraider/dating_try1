import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Persists the session token in the platform keystore / keychain.
class TokenStorage {
  TokenStorage({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  static const String _key = 'auth_token';

  final FlutterSecureStorage _storage;

  Future<String?> read() async {
    try {
      final value = await _storage.read(key: _key);
      return (value == null || value.isEmpty) ? null : value;
    } on Exception catch (e) {
      // E.g. Android keystore became unreadable after a backup restore:
      // treat as signed out instead of crashing on startup.
      debugPrint('TokenStorage.read failed: $e');
      await clear();
      return null;
    }
  }

  Future<void> write(String token) => _storage.write(key: _key, value: token);

  Future<void> clear() async {
    try {
      await _storage.delete(key: _key);
    } on Exception catch (e) {
      debugPrint('TokenStorage.clear failed: $e');
    }
  }
}
