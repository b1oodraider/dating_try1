import 'package:flutter/foundation.dart';

import '../../core/api/api_client.dart';
import '../../core/storage/discovery_filters_store.dart';
import '../../core/storage/token_storage.dart';

/// Owns the auth state: token in memory (on the API client) + secure storage.
class SessionController extends ChangeNotifier {
  SessionController({
    required this.api,
    required TokenStorage storage,
    DiscoveryFiltersStore? filtersStore,
  })  : _storage = storage,
        filtersStore = filtersStore ?? DiscoveryFiltersStore() {
    api.onUnauthorized = _onUnauthorized;
  }

  final DatingApiClient api;
  final TokenStorage _storage;

  /// Saved discovery filters (per account); kept on logout so signing in
  /// again restores them, wiped on account deletion.
  final DiscoveryFiltersStore filtersStore;

  bool _isAuthenticated = false;
  String? _pendingNotice;

  bool get isAuthenticated => _isAuthenticated;

  /// One-shot message for the UI (e.g. "session expired"), null if none.
  String? takeNotice() {
    final notice = _pendingNotice;
    _pendingNotice = null;
    return notice;
  }

  /// Loads a previously saved token. Call once before runApp.
  Future<void> restore() async {
    final token = await _storage.read();
    api.token = token;
    _isAuthenticated = api.hasToken;
  }

  Future<void> signIn(String token) async {
    api.token = token;
    try {
      await _storage.write(token);
    } on Exception catch (e) {
      // Still signed in for this app run, just not persisted.
      debugPrint('Failed to persist token: $e');
    }
    _isAuthenticated = true;
    notifyListeners();
  }

  /// Drops the local session immediately, then revokes the token on the
  /// server on a best-effort basis.
  Future<void> signOut() async {
    final token = api.token;
    await _clearLocal();
    if (token == null) return;
    try {
      await api.logout(token);
    } catch (e) {
      // Best effort: the token is already forgotten locally.
      debugPrint('Logout request failed: $e');
    }
  }

  /// Deletes the account on the server (`DELETE /me`), then wipes the local
  /// session and saved filters. Throws [ApiException] if the server refuses;
  /// the session is kept in that case.
  Future<void> deleteAccount() async {
    await api.deleteAccount();
    await filtersStore.clearAll();
    await _clearLocal();
  }

  // A 401 (expired token, or the account was deleted elsewhere) keeps the
  // saved filters: they are keyed by accountId, so another account never
  // sees them, and the same account gets them back after signing in again.
  Future<void> _onUnauthorized() async {
    _pendingNotice = 'Сессия истекла. Войдите снова';
    await _clearLocal();
  }

  Future<void> _clearLocal() async {
    api.token = null;
    await _storage.clear();
    if (_isAuthenticated) {
      _isAuthenticated = false;
      notifyListeners();
    }
  }
}
