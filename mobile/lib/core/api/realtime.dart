import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart' show debugPrint;
import 'package:http/http.dart' as http;

import 'api_client.dart';

/// One Server-Sent Event of `GET /events` (docs/api-contract.md, Realtime):
/// ready, heartbeat, message, match, match_closed, like_received, read.
class RealtimeEvent {
  const RealtimeEvent(this.name, this.data);

  final String name;

  /// Parsed `data:` JSON object; empty when the event has none.
  final Map<String, dynamic> data;
}

/// Keeps one event stream open while signed in and reconnects with a growing
/// pause. There is no replay: listeners refetch their data on `ready`.
class RealtimeConnection {
  RealtimeConnection(this._api);

  static const Duration _maxRetry = Duration(seconds: 30);

  final DatingApiClient _api;
  final StreamController<RealtimeEvent> _events =
      StreamController<RealtimeEvent>.broadcast();

  http.Client? _client;
  Timer? _retryTimer;
  Duration _retry = const Duration(seconds: 1);
  bool _closed = false;

  Stream<RealtimeEvent> get events => _events.stream;

  void start() => _connect();

  void close() {
    _closed = true;
    _retryTimer?.cancel();
    _client?.close();
    _events.close();
  }

  Future<void> _connect() async {
    if (_closed || !_api.hasToken) return;
    final client = http.Client();
    _client = client;
    try {
      final response = await _api.openEventStream(client);
      if (response.statusCode == 200) {
        await _read(response.stream);
      } else {
        debugPrint('Events stream refused: ${response.statusCode}');
      }
    } catch (e) {
      if (!_closed) debugPrint('Events stream dropped: $e');
    } finally {
      client.close();
    }
    if (_closed || !_api.hasToken) return;
    _retryTimer = Timer(_retry, _connect);
    _retry = _retry * 2 > _maxRetry ? _maxRetry : _retry * 2;
  }

  Future<void> _read(Stream<List<int>> bytes) async {
    String? name;
    final data = StringBuffer();
    await for (final line
        in bytes.transform(utf8.decoder).transform(const LineSplitter())) {
      if (line.isEmpty) {
        if (name != null) _emit(name, data.toString());
        name = null;
        data.clear();
      } else if (line.startsWith('event:')) {
        name = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        if (data.isNotEmpty) data.write('\n');
        data.write(line.substring(5).trimLeft());
      }
    }
  }

  void _emit(String name, String data) {
    if (_closed) return;
    if (name == 'ready') _retry = const Duration(seconds: 1);
    Object? json;
    try {
      json = data.isEmpty ? null : jsonDecode(data);
    } on FormatException {
      json = null;
    }
    _events.add(RealtimeEvent(
        name, json is Map<String, dynamic> ? json : const <String, dynamic>{}));
  }
}
